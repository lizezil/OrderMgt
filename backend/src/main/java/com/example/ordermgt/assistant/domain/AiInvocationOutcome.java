package com.example.ordermgt.assistant.domain;

/** How a model invocation ended. Stored as text in {@code ai_invocations.outcome}. */
public enum AiInvocationOutcome {
    OK,
    ERROR,
    TIMEOUT
}
