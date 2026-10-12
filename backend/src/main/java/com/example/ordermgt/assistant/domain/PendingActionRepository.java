package com.example.ordermgt.assistant.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PendingActionRepository {

    PendingAction save(PendingAction action);

    Optional<PendingAction> findByToken(String token);

    /**
     * Same as {@link #findByToken} but takes a row lock, so two concurrent confirmations of one
     * token cannot both execute. Must be called inside a transaction.
     */
    Optional<PendingAction> lockByToken(String token);

    List<PendingAction> findByIdIn(Collection<Long> ids);

    /** Bulk-expires PENDING rows past their deadline. Returns the number of rows touched. */
    int expireOverdue(Instant now);
}
