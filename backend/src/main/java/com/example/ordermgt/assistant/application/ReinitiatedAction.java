package com.example.ordermgt.assistant.application;

/**
 * The outcome of reinitiating an expired action (US-1.12): the freshly minted card -- same tool,
 * same arguments, new token, new TTL -- and the conversation it belongs to, so the caller can push
 * that conversation's new state (a live card is waiting again) to its status stream (ADR-0014).
 */
public record ReinitiatedAction(ActionCard card, Long conversationId) {}
