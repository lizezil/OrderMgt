package com.example.ordermgt.assistant.domain;

/** How a Run ends. Stored as text in {@code runs.status}. */
public enum RunStatus {
    /** The one non-terminal state: a Run is created RUNNING and leaves it exactly once. */
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
