package com.example.ordermgt.idempotency.domain;

import java.time.Instant;
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

    /**
     * Fails every key still IN_PROGRESS whose {@code expires_at} has passed. A key left IN_PROGRESS
     * this long means the process died between running the action and marking it done; failing it
     * reopens the key for a retry (see {@code IdempotencySweeper}).
     *
     * @return how many rows changed
     */
    int expireStaleInProgress(Instant now);
}
