package com.example.ordermgt.idempotency.domain;

import java.time.Instant;

/**
 * One key's worth of "this operation already happened (or is happening)". Lives in its own table so
 * no business aggregate has to carry the bookkeeping (ADR-0005).
 */
public record IdempotencyRecord(
        Long id,
        Long customerId,
        String operation,
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        String resultRef,
        String responseJson,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt) {}
