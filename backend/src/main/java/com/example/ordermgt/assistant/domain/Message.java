package com.example.ordermgt.assistant.domain;

import java.time.Instant;

/**
 * One persisted turn. This is what the user sees and what memory replays.
 *
 * <p>{@code pendingActionId} is set when the turn presented a confirmation card, so history can
 * render that card again instead of a dead line of text.
 */
public record Message(
        Long id,
        Long conversationId,
        Role role,
        String content,
        Long pendingActionId,
        Instant createdAt) {

    /**
     * messages.content is VARCHAR(8000); an oversized reply would be rejected by the database, so
     * it is capped here rather than blowing up the write.
     */
    public static final int CONTENT_MAX = 8000;

    public static Message customer(Long conversationId, String content, Instant at) {
        return new Message(null, conversationId, Role.CUSTOMER, cap(content), null, at);
    }

    public static Message assistant(Long conversationId, String content, Instant at) {
        return new Message(null, conversationId, Role.ASSISTANT, cap(content), null, at);
    }

    public static Message assistantWithAction(
            Long conversationId, String content, Long pendingActionId, Instant at) {
        return new Message(null, conversationId, Role.ASSISTANT, cap(content), pendingActionId, at);
    }

    private static String cap(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= CONTENT_MAX
                ? content
                : content.substring(0, CONTENT_MAX - 14) + "...[truncated]";
    }
}
