package com.example.ordermgt.idempotency.domain;

import java.util.Optional;

public interface IdempotencyRepository {

    Optional<IdempotencyRecord> find(Long customerId, String operation, String idempotencyKey);

    /**
     * Claims the key. Returns the stored record (with its generated id) on success, or {@link
     * Optional#empty()} when the key is already claimed -- the unique constraint is the arbiter,
     * not a read-then-write check.
     */
    Optional<IdempotencyRecord> tryClaim(IdempotencyRecord record);

    void complete(Long id, String responseJson);

    void markFailed(Long id);

    void reclaim(Long id);
}
