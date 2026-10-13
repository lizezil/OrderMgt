package com.example.ordermgt.assistant.application;

/**
 * The outcome of confirming or rejecting an action.
 *
 * <p>{@code replayed} says the call was a repeat: the operation did NOT run again, and the caller
 * is looking at the earlier decision. Only the first confirmation carries the tool's full result
 * payload.
 *
 * <p>{@code conversationId} is the conversation the card belonged to, so the caller can push that
 * conversation's new state (WAITING_APPROVAL -> IDLE) to its status stream (ADR-0014).
 */
public record ActionResolution(
        String token, String status, String resultJson, boolean replayed, Long conversationId) {}
