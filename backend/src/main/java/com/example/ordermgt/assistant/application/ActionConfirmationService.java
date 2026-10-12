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

        switch (action.getStatus()) {
            case CONFIRMED -> {
                return replayed(action);
            }
            case REJECTED -> throw new BusinessException(ErrorCode.CONFLICT, "该操作已被拒绝，无法确认");
            case EXPIRED -> throw new BusinessException(ErrorCode.ACTION_EXPIRED, "该待确认操作已过期");
            case PENDING -> {
                if (action.isExpiredAt(now)) {
                    throw new BusinessException(ErrorCode.ACTION_EXPIRED, "该待确认操作已过期");
                }
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
                                                "待确认操作指向了非变更工具: " + action.getToolName()));

        ToolResult result = spec.handler().execute(customerId, action.getArgumentsJson());
        action.resolve(PendingActionStatus.CONFIRMED, result.ref(), now);
        pendingActionRepository.save(action);

        return new ActionResolution(
                action.getToken(), action.getStatus().name(), result.payloadJson(), false);
    }

    @Transactional
    public ActionResolution reject(Long customerId, String token) {
        PendingAction action = lockOwned(customerId, token);

        switch (action.getStatus()) {
            case REJECTED -> {
                return replayed(action);
            }
            case CONFIRMED -> throw new BusinessException(ErrorCode.CONFLICT, "该操作已确认，无法拒绝");
            case EXPIRED -> throw new BusinessException(ErrorCode.ACTION_EXPIRED, "该待确认操作已过期");
            case PENDING -> {
                if (action.isExpiredAt(Instant.now())) {
                    throw new BusinessException(ErrorCode.ACTION_EXPIRED, "该待确认操作已过期");
                }
            }
        }

        action.resolve(PendingActionStatus.REJECTED, null, Instant.now());
        pendingActionRepository.save(action);
        return new ActionResolution(action.getToken(), action.getStatus().name(), null, false);
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
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "待确认操作不存在"));
        // Someone else's token is reported as absent to the caller, but we keep the
        // distinct error internally so logs show what actually happened.
        if (!action.isOwnedBy(customerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该待确认项");
        }
        return action;
    }
}
