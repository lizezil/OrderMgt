package com.example.ordermgt.assistant.domain;

import lombok.Getter;

import java.time.Instant;

/**
 * Aggregate root for memory. "New conversation" is nothing more than starting one of
 * these: memory never crosses conversations.
 */
@Getter
public class Conversation {

    /** Title is derived from the first customer message, truncated to this many chars. */
    public static final int TITLE_MAX = 30;

    private Long id;
    private final Long customerId;
    private String title;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastMessageAt;

    private Conversation(Long id, Long customerId, String title,
                         Instant createdAt, Instant updatedAt, Instant lastMessageAt) {
        this.id = id;
        this.customerId = customerId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastMessageAt = lastMessageAt;
    }

    public static Conversation start(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        Instant now = Instant.now();
        return new Conversation(null, customerId, null, now, now, now);
    }

    public static Conversation rehydrate(Long id, Long customerId, String title,
                                        Instant createdAt, Instant updatedAt, Instant lastMessageAt) {
        return new Conversation(id, customerId, title, createdAt, updatedAt, lastMessageAt);
    }

    public void assignId(Long id) {
        this.id = id;
    }

    /** Untitled conversations take the first thing the customer said as their title. */
    public void titleFrom(String firstCustomerMessage) {
        if (title != null && !title.isBlank()) {
            return;
        }
        if (firstCustomerMessage == null || firstCustomerMessage.isBlank()) {
            return;
        }
        String flat = firstCustomerMessage.strip().replaceAll("\\s+", " ");
        this.title = flat.length() <= TITLE_MAX ? flat : flat.substring(0, TITLE_MAX) + "…";
    }

    public void touch(Instant at) {
        this.updatedAt = at;
        this.lastMessageAt = at;
    }
}
