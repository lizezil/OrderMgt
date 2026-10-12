package com.example.ordermgt.assistant.domain;

/**
 * Server-side facts the model must not invent (which skus exist, at what price).
 * They are injected into the system prompt so tool arguments are grounded in the
 * catalog instead of hallucinated.
 */
public interface GroundingProvider {

    String facts();
}
