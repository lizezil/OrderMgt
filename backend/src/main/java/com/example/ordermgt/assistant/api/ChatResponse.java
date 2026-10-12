package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionCard;
import java.util.List;

/**
 * A chat turn. Anything that mutates data comes back as a card in {@code actions} -- never as
 * something already applied.
 */
public record ChatResponse(
        Long conversationId,
        String reply,
        List<ToolCallView> executed,
        List<ActionCard> actions,
        int promptTokens,
        int completionTokens) {

    public record ToolCallView(String name, String resultJson) {}
}
