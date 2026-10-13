package com.example.ordermgt.assistant.api;

/**
 * The first frame of a reattach (ADR-0014): the text accumulated so far on the Run being watched.
 * It is a stateless read of "what has been said", never a per-delta cache and not a resume point
 * (ADR-0009) -- the client renders it and then appends the tokens that follow.
 */
public record RunTextResponse(String text) {}
