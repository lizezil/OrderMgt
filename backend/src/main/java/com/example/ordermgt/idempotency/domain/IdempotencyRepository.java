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

    /**
     * Records that the action itself succeeded but storing its response failed. Distinct from
     * {@link #markFailed} because the retry semantics are opposite: FAILED may be reclaimed and
     * re-run, RESULT_UNKNOWN must be refused forever (re-running would duplicate committed work).
     */
    void markResultUnknown(Long id);

    /**
     * Reclaims a FAILED record for a retry by flipping it to IN_PROGRESS. The transition is
     * conditional on the row still being FAILED, so under concurrent retries exactly one caller
     * wins; an unconditional update would let every retrying thread acquire execution rights and
     * run the action again. The expiry is refreshed so the sweeper cannot fail the record mid-retry
     * -- that would reopen the key to a second concurrent execution.
     *
     * @return true when this caller won the race, false when someone else already reclaimed it
     */
    boolean reclaim(Long id, Instant newExpiresAt);

    /**
     * Deletes every key whose {@code expires_at} has passed, regardless of status. Deletion is what
     * makes retention real: the key becomes claimable again, replays stop, and the table stays
     * bounded. A key left IN_PROGRESS this long means the process died between running the action
     * and marking it done; after retention the duplicate risk of a re-run is accepted (see {@code
     * IdempotencySweeper}).
     *
     * @return how many rows were deleted
     */
    int deleteExpired(Instant now);
}
