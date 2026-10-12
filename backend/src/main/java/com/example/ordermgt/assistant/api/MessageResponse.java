package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionCard;
import java.time.Instant;

/**
 * One line of history. {@code action} is present when this turn showed a confirmation card,
 * carrying that card's current status.
 */
public record MessageResponse(
        Long id, String role, String content, Instant createdAt, ActionCard action) {}
