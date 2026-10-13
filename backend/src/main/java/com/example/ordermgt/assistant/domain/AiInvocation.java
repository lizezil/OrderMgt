package com.example.ordermgt.assistant.domain;

import java.time.Instant;

/**
 * One model call, kept for audit and observability (the {@code ai_invocations} table).
 *
 * <p>Separate from {@link Message} on purpose: {@code messages} is what the customer sees, this is
 * what operations sees. A failed or timed-out call leaves a row here even though it produced no
 * message at all.
 */
public record AiInvocation(
        Long id,
        Long conversationId,
        Long customerId,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer latencyMs,
        AiInvocationOutcome outcome,
        String error,
        String toolCallsJson,
        Instant createdAt) {

    /** ai_invocations.error is VARCHAR(1000); a long provider message must not fail the insert. */
    public static final int ERROR_MAX = 1000;

    public static AiInvocation record(
            Long conversationId,
            Long customerId,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            AiInvocationOutcome outcome,
            String error,
            String toolCallsJson) {
        return new AiInvocation(
                null,
                conversationId,
                customerId,
                // model is NOT NULL in the table, and a fake turn may not name one: an audit row
                // reading "unknown" beats losing the row entirely.
                model == null || model.isBlank() ? "unknown" : model,
                promptTokens,
                completionTokens,
                latencyMs,
                outcome,
                truncate(error),
                toolCallsJson,
                Instant.now());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }
}
