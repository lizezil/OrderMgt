package com.example.ordermgt.assistant.api;

/**
 * A streaming turn that is waiting its turn on a busy conversation, as carried by the {@code
 * queued} frame of its own stream. {@code itemId} is the in-memory handle {@code DELETE
 * /conversations/{id}/queue/{itemId}} needs -- without it the customer could see the line but not
 * touch it; it is a runtime id with no row behind it, so it dies with the process (ADR-0012).
 *
 * @param position 1-based place in this conversation's line
 */
public record QueuedTurnResponse(String itemId, int position) {}
