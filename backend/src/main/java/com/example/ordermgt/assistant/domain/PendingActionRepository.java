package com.example.ordermgt.assistant.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PendingActionRepository {

    PendingAction save(PendingAction action);

    Optional<PendingAction> findByToken(String token);

    /**
     * Same as {@link #findByToken} but takes a row lock, so two concurrent confirmations of one
     * token cannot both execute. Must be called inside a transaction.
     */
    Optional<PendingAction> lockByToken(String token);

    List<PendingAction> findByIdIn(Collection<Long> ids);

    /**
     * Which of {@code conversationIds} have a live card right now -- PENDING and not yet past its
     * deadline, checked against {@code now} because the sweeper may not have run since it lapsed.
     * One query, so the sidebar derives WAITING_APPROVAL without an N+1.
     */
    Set<Long> livePendingConversationIds(Collection<Long> conversationIds, Instant now);

    /** Bulk-expires PENDING rows past their deadline. Returns the number of rows touched. */
    int expireOverdue(Instant now);
}
