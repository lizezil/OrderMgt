package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>It also owns reinitiation (US-1.12): turning an expired card back into a fresh proposal runs
 * no tool either -- it mints a new card from the stored one and leaves everything else alone.
 *
 * <p>These methods ARE transactional, unlike the AI call path: the work here is a fast,
 * database-only mutation (create/cancel an order) plus the state change, and those must commit
 * together.
 */
@RequiredArgsConstructor
@Service
public class ActionConfirmationService {

    private final PendingActionRepository pendingActionRepository;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final ActionCardFactory actionCardFactory;

    /** Configured in minutes (the shape it arrives in); {@link #actionTtl()} is how it is used. */
    @Value("${assistant.action.ttl-minutes:15}")
    private final long actionTtlMinutes;

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
                action.getToken(),
                action.getStatus().name(),
                result.payloadJson(),
                false,
                action.getConversationId());
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
        return new ActionResolution(
                action.getToken(),
                action.getStatus().name(),
                null,
                false,
                action.getConversationId());
    }

    /**
     * 重新发起（US-1.12）：把过期卡片背后的**同一个提议**重新摆出来——不重问模型、不改参数，用卡片 存下的工具名与参数造一张全新的 PendingAction（新 token、新
     * TTL 从头起算），并像一次新提议那样 进历史。它不执行任何变更：能跑到变更 handler 的仍然只有确认入口。
     *
     * <p>只有真的过期（EXPIRED，或清扫器没跑、读取时已过 deadline 的 PENDING——口径同 US-5.3）换得到 新卡片。仍活着或已终结的卡片都是
     * CONFLICT：拒绝是客户的意思表示，重来该由客户重新开口，而不是 换张卡片再来一次。原卡片保持 EXPIRED，旧 token 依旧 ACTION_EXPIRED。
     */
    @Transactional
    public ReinitiatedAction reinitiate(Long customerId, String token) {
        PendingAction action = lockOwned(customerId, token);
        if (!action.isDeadAt(Instant.now())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "a "
                            + action.getStatus().name().toLowerCase(Locale.ROOT)
                            + " pending action cannot be reinitiated");
        }

        PendingAction renewed =
                pendingActionRepository.save(
                        PendingAction.propose(
                                customerId,
                                action.getConversationId(),
                                action.getToolName(),
                                action.getArgumentsJson(),
                                action.getSummary(),
                                actionTtl()));
        ActionCard card = actionCardFactory.card(renewed);
        Message cardMessage =
                conversationService.appendAssistantMessage(
                        action.getConversationId(), card.summary(), renewed.getId());
        conversationService.linkActionToMessage(renewed, cardMessage.id());
        return new ReinitiatedAction(card, action.getConversationId());
    }

    private Duration actionTtl() {
        return Duration.ofMinutes(actionTtlMinutes);
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
        return new ActionResolution(
                action.getToken(),
                action.getStatus().name(),
                resultJson,
                true,
                action.getConversationId());
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
