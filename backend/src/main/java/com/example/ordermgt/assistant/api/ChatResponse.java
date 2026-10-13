package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionCard;
import java.util.List;

/**
 * A chat turn. Anything that mutates data comes back as a card in {@code actions} -- never as
 * something already applied. {@code model} and {@code latencyMs} describe the model call behind
 * this turn, so the UI can show how long the assistant took.
 */
public record ChatResponse(
        Long conversationId,
        String reply,
        List<ToolCallView> executed,
        List<ActionCard> actions,
        int promptTokens,
        int completionTokens,
        String model,
        int latencyMs) {

    public record ToolCallView(String name, String resultJson) {}
}
