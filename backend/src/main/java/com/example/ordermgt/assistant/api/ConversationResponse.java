package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.domain.Conversation;
import java.time.Instant;

public record ConversationResponse(
        Long id, String title, Instant createdAt, Instant updatedAt, Instant lastMessageAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                conversation.getLastMessageAt());
    }
}
