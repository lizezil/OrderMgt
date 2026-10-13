package com.example.ordermgt.assistant.domain;

import java.time.Instant;

/**
 * One execution instance of a conversation: a customer message triggering a prepare, a model call
 * and the persist that follows. Subordinate to its Conversation, like {@link Message} (ADR-0011) --
 * it carries no content, only the lifecycle the sidebar derives its state from.
 *
 * <p>The only invariant is {@code RUNNING -> one terminal state}, enforced by {@link #finish}. "At
 * most one RUNNING run per conversation" is not a property of a single row and lives in the
 * in-process registry (ADR-0012).
 */
public record Run(
        Long id,
        Long conversationId,
        RunStatus status,
        String error,
        Instant startedAt,
        Instant endedAt) {

    /** runs.error is VARCHAR(1000); a long provider message must not fail the write. */
    public static final int ERROR_MAX = 1000;

    public static Run start(Long conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("a run needs a conversation");
        }
        return new Run(null, conversationId, RunStatus.RUNNING, null, Instant.now(), null);
    }

    public static Run rehydrate(
            Long id,
            Long conversationId,
            RunStatus status,
            String error,
            Instant startedAt,
            Instant endedAt) {
        return new Run(id, conversationId, status, error, startedAt, endedAt);
    }

    public boolean isRunning() {
        return status == RunStatus.RUNNING;
    }

    /**
     * The only way out of RUNNING: one terminal state, an optional reason (truncated to fit the
     * column), and the end time. A finished Run is a bug, not a no-op -- callers hold the RUNNING
     * instance they got from {@link #start}.
     */
    public Run finish(RunStatus terminal, String error, Instant at) {
        if (terminal == null || !terminal.isTerminal()) {
            throw new IllegalArgumentException("a run can only finish into a terminal state");
        }
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("run " + id + " is already " + status);
        }
        return new Run(id, conversationId, terminal, truncate(error), startedAt, at);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }
}
