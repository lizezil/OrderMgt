package com.example.ordermgt.assistant.domain;

/** Conversation lifecycle, stored in {@code conversations.status}. */
public enum ConversationStatus {
    ACTIVE,
    /** Hidden from the default list, still readable and restorable -- never deleted (ADR-0013). */
    ARCHIVED
}
