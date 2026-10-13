package com.example.ordermgt.assistant.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A mutating operation the model proposed but nobody has executed. It is the only way a mutating
 * tool can ever run (ADR-0001): the model proposes, the customer confirms.
 *
 * <p>The {@code token} is the capability. It is bound to the customer who started it, so knowing a
 * token is not enough -- the caller must also be that customer.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PendingAction {

    /**
     * pending_actions.arguments_json is VARCHAR(4000). Tool arguments are small, so an oversized
     * payload means something is wrong upstream and must be rejected rather than silently truncated
     * into an executable-but-wrong action.
     */
    public static final int ARGUMENTS_MAX = 4000;

    /** 24 random bytes = a 192-bit, unguessable confirmation token (48 hex chars). */
    private static final int TOKEN_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Long id;
    private final String token;
    private final Long customerId;
    private final Long conversationId;
    private Long sourceMessageId;
    private final String toolName;
    private final String argumentsJson;
    private final String summary;
    private PendingActionStatus status;
    private final Instant expiresAt;
    private Instant resolvedAt;
    private String resultRef;
    private final Instant createdAt;

    public static PendingAction propose(
            Long customerId,
            Long conversationId,
            String toolName,
            String argumentsJson,
            String summary,
            Duration ttl) {
        if (customerId == null || conversationId == null) {
            throw new IllegalArgumentException(
                    "a pending action needs a customer and a conversation");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (argumentsJson != null && argumentsJson.length() > ARGUMENTS_MAX) {
            throw new IllegalArgumentException("tool arguments exceed " + ARGUMENTS_MAX + " chars");
        }
        Instant now = Instant.now();
        return new PendingAction(
                null,
                newToken(),
                customerId,
                conversationId,
                null,
                toolName,
                argumentsJson,
                summary,
                PendingActionStatus.PENDING,
                now.plus(ttl),
                null,
                null,
                now);
    }

    public static PendingAction rehydrate(
            Long id,
            String token,
            Long customerId,
            Long conversationId,
            Long sourceMessageId,
            String toolName,
            String argumentsJson,
            String summary,
            PendingActionStatus status,
            Instant expiresAt,
            Instant resolvedAt,
            String resultRef,
            Instant createdAt) {
        return new PendingAction(
                id,
                token,
                customerId,
                conversationId,
                sourceMessageId,
                toolName,
                argumentsJson,
                summary,
                status,
                expiresAt,
                resolvedAt,
                resultRef,
                createdAt);
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public void attachToMessage(Long messageId) {
        this.sourceMessageId = messageId;
    }

    public boolean isOwnedBy(Long otherCustomerId) {
        return customerId.equals(otherCustomerId);
    }

    public boolean isPending() {
        return status == PendingActionStatus.PENDING;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Outcome of {@link #adjudicate}: what a resolution attempt may do right now. */
    public enum Verdict {
        /** The action is live and this resolution may be applied. */
        PROCEED,
        /** The same resolution already happened -- report the earlier outcome, execute nothing. */
        REPLAY,
        /** The opposite resolution already happened -- refuse. */
        CONFLICT,
        /** The action is dead (swept, or past its TTL) -- refuse as expired. */
        EXPIRED
    }

    /**
     * The state-machine gate both resolutions share (ADR-0001): applying one resolution twice must
     * not run the action twice, the opposite resolution must never apply, and a dead action is
     * never resolvable. Translating a verdict into an error and an HTTP status belongs to the
     * caller; the decision itself lives here, next to the status invariants it enforces.
     *
     * @param outcome the terminal state this call wants to reach (CONFIRMED or REJECTED)
     * @param now the resolution time, checked against the TTL
     */
    public Verdict adjudicate(PendingActionStatus outcome, Instant now) {
        if (outcome != PendingActionStatus.CONFIRMED && outcome != PendingActionStatus.REJECTED) {
            throw new IllegalArgumentException("only CONFIRMED or REJECTED can resolve an action");
        }
        if (status == outcome) {
            return Verdict.REPLAY;
        }
        if (status != PendingActionStatus.PENDING) {
            // Not ours and not PENDING: the opposite terminal state, or a dead row.
            return status == PendingActionStatus.EXPIRED ? Verdict.EXPIRED : Verdict.CONFLICT;
        }
        return isExpiredAt(now) ? Verdict.EXPIRED : Verdict.PROCEED;
    }

    public void resolve(PendingActionStatus terminal, String resultRef, Instant at) {
        if (!isPending()) {
            throw new IllegalStateException("action " + token + " is already " + status);
        }
        if (terminal != PendingActionStatus.CONFIRMED && terminal != PendingActionStatus.REJECTED) {
            throw new IllegalArgumentException("only CONFIRMED or REJECTED can resolve an action");
        }
        this.status = terminal;
        this.resultRef = resultRef;
        this.resolvedAt = at;
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
