package com.example.ordermgt.assistant.application;

import java.time.Instant;
import java.util.List;

/**
 * The confirmation card the frontend renders. Presentation only -- it holds no business
 * rule, it just describes a PendingAction and how to act on it.
 */
public record ActionCard(String token, String toolName, String title, String summary,
                         String argumentsJson, List<Field> fields, String confirmEndpoint,
                         String rejectEndpoint, Instant expiresAt, String status) {

    public record Field(String label, String value) {
    }
}
