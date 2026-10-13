package com.example.ordermgt.assistant.api;

import java.util.List;

/**
 * The `queue` frame on the customer's status stream (ADR-0014): which waiting items a conversation
 * has, front first. The items carry no text -- a queued send is not in the database, and its words
 * only ever lived in the client that typed them -- so another tab learns the line's shape and ids,
 * which is enough to see it and to delete an item, without pretending to know what it says.
 */
public record QueueEventResponse(Long conversationId, List<QueuedTurnResponse> items) {}
