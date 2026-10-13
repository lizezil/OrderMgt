package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place a mutating tool ever runs (ADR-0001).
 *
 * <p>Concurrency is handled by locking the row (`SELECT ... FOR UPDATE`) and then checking the
 * state machine inside the same transaction: the second of two simultaneous confirmations blocks on
 * the lock, and by the time it reads the row the status is already CONFIRMED, so it replays instead
 * of executing. Expiry is checked on the same read.
 *
 * <p>This method IS transactional, unlike the AI call path: the work here is a fast, database-only
 * mutation (create/cancel an order) plus the state change, and those must commit together.
 */
@RequiredArgsConstructor
@Service
public class ActionConfirmationService {

    private final PendingActionRepository pendingActionRepository;
    private final ToolRegistry toolRegistry;

    @Transactional
    public ActionResolution confirm(Long customerId, String token) {
        PendingAction action = lockOwned(customerId, token);
        Instant now = Instant.now();

        switch (action.adjudicate(PendingActionStatus.CONFIRMED, now)) {
            case REPLAY -> {
                return replayed(action);
            }
            case EXPIRED -> throw expired();
            case CONFLICT -> throw conflict(action);
            case PROCEED -> {
                /* the action is live: run it below */
            }
        }

        ToolSpec spec =
                toolRegistry
                        .find(action.getToolName())
                        .filter(ToolSpec::mutating)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INTERNAL_ERROR,
                                                "pending action points at a non-mutating tool: "
                                                        + action.getToolName()));

        ToolResult result = spec.handler().execute(customerId, action.getArgumentsJson());
        action.resolve(PendingActionStatus.CONFIRMED, result.ref(), now);
        pendingActionRepository.save(action);

        return new ActionResolution(
                action.getToken(), action.getStatus().name(), result.payloadJson(), false);
    }

    @Transactional
    public ActionResolution reject(Long customerId, String token) {
        PendingAction action = lockOwned(customerId, token);

        switch (action.adjudicate(PendingActionStatus.REJECTED, Instant.now())) {
            case REPLAY -> {
                return replayed(action);
            }
            case EXPIRED -> throw expired();
            case CONFLICT -> throw conflict(action);
            case PROCEED -> {
                /* the action is live: reject it below */
            }
        }

        action.resolve(PendingActionStatus.REJECTED, null, Instant.now());
        pendingActionRepository.save(action);
        return new ActionResolution(action.getToken(), action.getStatus().name(), null, false);
    }

    private static BusinessException expired() {
        return new BusinessException(ErrorCode.ACTION_EXPIRED, "the pending action has expired");
    }

    /**
     * The conflicting status (CONFIRMED or REJECTED) is exactly what the caller is not doing, so
     * the message needs no direction from the domain.
     */
    private static BusinessException conflict(PendingAction action) {
        return new BusinessException(
                ErrorCode.CONFLICT,
                "the pending action has already been "
                        + action.getStatus().name().toLowerCase(Locale.ROOT));
    }

    /**
     * A repeat call is not an error and not a second execution: report the earlier outcome and hand
     * back the reference to whatever it produced. The reference is the one the tool itself named,
     * never a field scraped back out of its payload.
     */
    private ActionResolution replayed(PendingAction action) {
        String resultJson =
                action.getResultRef() == null
                        ? null
                        : "{\"replayed\":true,\"ref\":\"" + action.getResultRef() + "\"}";
        return new ActionResolution(action.getToken(), action.getStatus().name(), resultJson, true);
    }

    private PendingAction lockOwned(Long customerId, String token) {
        PendingAction action =
                pendingActionRepository
                        .lockByToken(token)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "pending action not found"));
        // Someone else's token is reported as absent to the caller, but we keep the
        // distinct error internally so logs show what actually happened.
        if (!action.isOwnedBy(customerId)) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN, "access to this pending action is forbidden");
        }
        return action;
    }
}
