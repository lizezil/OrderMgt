package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionCard;
import com.example.ordermgt.assistant.application.AssistantResult;
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

    public static ChatResponse from(AssistantResult result) {
        return new ChatResponse(
                result.conversationId(),
                result.reply(),
                result.executed().stream()
                        .map(e -> new ToolCallView(e.name(), e.resultJson()))
                        .toList(),
                result.actions(),
                result.usage().promptTokens(),
                result.usage().completionTokens());
    }
}
