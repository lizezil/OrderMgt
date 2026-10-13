package com.example.ordermgt.assistant.api;

/**
 * What a stopped turn closes with: the half reply, which the server has already persisted as an
 * ordinary ASSISTANT message (ADR-0012). The client replaces its streaming placeholder with this
 * exact text, so what is on screen equals what the history now holds.
 */
public record RunStoppedResponse(String reply) {}
