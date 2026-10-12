package com.example.ordermgt.idempotency.domain;

public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
