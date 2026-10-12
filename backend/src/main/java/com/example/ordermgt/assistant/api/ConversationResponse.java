package com.example.ordermgt.assistant.api;

import java.time.Instant;

public record ConversationResponse(
        Long id, String title, Instant createdAt, Instant updatedAt, Instant lastMessageAt) {}
