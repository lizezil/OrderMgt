package com.example.ordermgt.assistant.domain;

/**
 * Server-side facts the model must not invent, injected into the system prompt so its arguments and
 * its answers are grounded in what the service knows instead of guessed.
 *
 * <p>Three kinds today: the catalog (which skus exist, at what price -- see ADR-0006), the order
 * this conversation most recently placed, and the proposals of this conversation still awaiting the
 * customer's answer (US-1.13). The second exists because a customer's confident "that order" has no
 * other route into the prompt: a confirmation's outcome is shown by its card and never written into
 * the transcript, so the order number is otherwise nowhere for the model to find. The third is the
 * same problem one step earlier -- a pending card's own words are the only record of what was
 * proposed, and "just proposed that one" needs the list to be determinate.
 *
 * <p>Scoped by conversation on purpose -- "just placed" and "just proposed" are facts about a
 * conversation, not about a customer's whole history.
 */
public interface GroundingProvider {

    /**
     * @param customerId whose facts these are; it is also what keeps the conversation lookup from
     *     ever surfacing another customer's order.
     */
    String facts(Long customerId, Long conversationId);
}
