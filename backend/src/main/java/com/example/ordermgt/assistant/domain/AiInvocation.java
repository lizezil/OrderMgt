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
                model,
                promptTokens,
                completionTokens,
                latencyMs,
                outcome,
                error,
                toolCallsJson,
                Instant.now());
    }
}
