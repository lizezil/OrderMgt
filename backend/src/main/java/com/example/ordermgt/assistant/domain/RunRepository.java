package com.example.ordermgt.assistant.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Port for the execution rows the conversation state is derived from (ADR-0011). */
public interface RunRepository {

    /**
     * Inserts a new Run (id null) or updates an existing one: the same save shape as the others.
     */
    Run save(Run run);

    /** Which of {@code conversationIds} have a RUNNING run right now, in one query. */
    Set<Long> runningConversationIds(Collection<Long> conversationIds);

    /**
     * Closes every Run still marked RUNNING as INTERRUPTED, recording why and when (ADR-0011). No
     * living process can be behind such a row -- that is exactly what startup reconciliation
     * asserts. One statement for the whole table, not a row-by-row fixup.
     *
     * @return how many rows were interrupted
     */
    int interruptRunning(String reason, Instant at);
}
