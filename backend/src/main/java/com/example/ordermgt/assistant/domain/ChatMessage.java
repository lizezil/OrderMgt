package com.example.ordermgt.assistant.domain;

/**
 * A turn as the model sees it. Distinct from {@link Message} on purpose: Message is
 * what we persist and show the customer, ChatMessage is what we replay into the prompt.
 */
public record ChatMessage(Role role, String content) {

    public static ChatMessage of(Message message) {
        return new ChatMessage(message.role(), message.content());
    }
}
