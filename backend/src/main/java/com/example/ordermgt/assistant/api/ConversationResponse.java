package com.example.ordermgt.assistant.api;

import java.time.Instant;

/**
 * A conversation on the wire. {@code status} is the stored lifecycle (ACTIVE / ARCHIVED); {@code
 * state} is derived on every read (IDLE / RUNNING / WAITING_APPROVAL) -- see ADR-0011. {@code
 * runId} names the Run under way when {@code state} is RUNNING (null otherwise): the handle a stop
 * or a reattach needs.
 */
public record ConversationResponse(
        Long id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt,
        String status,
        String state,
        Long runId) {}
