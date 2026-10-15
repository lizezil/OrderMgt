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
     * The most recently confirmed action of one conversation, if any -- what the customer means by
     * "that order I just placed". Scoped by customer as well as by conversation, so a fact handed
     * to the model can never be another customer's order however the conversation id was obtained.
     */
    Optional<PendingAction> findLatestConfirmed(Long customerId, Long conversationId);

    /**
     * The live cards of one conversation, oldest first -- the proposals the customer can still mean
     * by "that one I just proposed". PENDING and not yet past its deadline, both checked against
     * {@code now} because the sweeper may not have run since one lapsed (US-1.13, same reading as
     * {@link #livePendingConversationIds}). Scoped by customer as well as by conversation.
     */
    List<PendingAction> findLivePending(Long customerId, Long conversationId, Instant now);

    /**
     * Which of {@code conversationIds} have a live card right now -- PENDING and not yet past its
     * deadline, checked against {@code now} because the sweeper may not have run since it lapsed.
     * One query, so the sidebar derives WAITING_APPROVAL without an N+1.
     */
    Set<Long> livePendingConversationIds(Collection<Long> conversationIds, Instant now);

    /** Bulk-expires PENDING rows past their deadline. Returns the number of rows touched. */
    int expireOverdue(Instant now);
}
