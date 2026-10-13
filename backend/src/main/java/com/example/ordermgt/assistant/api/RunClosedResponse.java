package com.example.ordermgt.assistant.api;

/**
 * How a reattach ends (ADR-0014): the Run is no longer live here, with the status it settled on.
 * The connection closes right after, so a subscriber that arrived too late -- or one whose Run just
 * finished -- is told so instead of waiting on a stream that will never speak again.
 */
public record RunClosedResponse(Long runId, String status) {}
