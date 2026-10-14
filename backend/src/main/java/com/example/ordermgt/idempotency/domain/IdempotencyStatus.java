package com.example.ordermgt.idempotency.domain;

public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    /**
     * The action ran and committed, but its response could not be stored. The only safe answer to a
     * retry is refusal: reclaiming this key would run the action a second time (a duplicate order),
     * which is exactly what the key exists to prevent.
     */
    RESULT_UNKNOWN
}
