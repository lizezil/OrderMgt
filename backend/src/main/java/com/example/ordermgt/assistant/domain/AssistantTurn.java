package com.example.ordermgt.assistant.domain;

import java.util.List;

/**
 * One finished model turn.
 *
 * <p>{@code model} and {@code latencyMs} are audit facts the model call produced; they travel with
 * the turn so the application layer can persist them without reaching back into the adapter.
 */
public record AssistantTurn(
        String text, List<ProposedToolCall> toolCalls, AiUsage usage, String model, int latencyMs) {

    /** A turn with no audit facts (used by fakes and by the empty/failure paths). */
    public static AssistantTurn of(String text, List<ProposedToolCall> toolCalls, AiUsage usage) {
        return new AssistantTurn(text, toolCalls, usage, null, 0);
    }
}
