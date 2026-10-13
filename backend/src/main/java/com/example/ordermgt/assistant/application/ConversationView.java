package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationState;

/**
 * A conversation paired with the state derived for it on this read (ADR-0011), and the id of the
 * Run under way if there is one -- the handle a stop or a reattach needs. {@code runId} is null
 * exactly when the state is not RUNNING.
 */
public record ConversationView(Conversation conversation, ConversationState state, Long runId) {

    /** A conversation that cannot have a Run or a live card yet, i.e. what just started. */
    public static ConversationView idle(Conversation conversation) {
        return new ConversationView(conversation, ConversationState.IDLE, null);
    }
}
