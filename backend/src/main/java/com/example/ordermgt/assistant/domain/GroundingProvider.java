package com.example.ordermgt.assistant.domain;

/**
 * Server-side facts the model must not invent, injected into the system prompt so its arguments and
 * its answers are grounded in what the service knows instead of guessed.
 *
 * <p>Two kinds today: the catalog (which skus exist, at what price -- see ADR-0006) and the order
 * this conversation most recently placed. The second one exists because a customer's confident
 * "that order" has no other route into the prompt: a confirmation's outcome is shown by its card
 * and never written into the transcript, so the order number is otherwise nowhere for the model to
 * find.
 *
 * <p>Scoped by conversation on purpose -- "just placed" is a fact about a conversation, not about a
 * customer's whole history.
 */
public interface GroundingProvider {

    /**
     * @param customerId whose facts these are; it is also what keeps the conversation lookup from
     *     ever surfacing another customer's order.
     */
    String facts(Long customerId, Long conversationId);
}
