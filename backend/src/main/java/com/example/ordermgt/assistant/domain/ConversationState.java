package com.example.ordermgt.assistant.domain;

/**
 * What the sidebar shows for a conversation. Derived on every read, never stored (ADR-0011): a
 * RUNNING run wins, then a live confirmation card, otherwise idle.
 *
 * <p>{@code FAILED} is deliberately not a value -- failure already leaves an {@code ai_invocations}
 * row, and a permanent "failed" badge would only add noise.
 */
public enum ConversationState {
    IDLE,
    RUNNING,
    WAITING_APPROVAL;

    public static ConversationState derive(boolean hasRunningRun, boolean hasLivePendingAction) {
        if (hasRunningRun) {
            return RUNNING;
        }
        if (hasLivePendingAction) {
            return WAITING_APPROVAL;
        }
        return IDLE;
    }
}
