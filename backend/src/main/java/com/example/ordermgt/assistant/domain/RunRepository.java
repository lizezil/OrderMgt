package com.example.ordermgt.assistant.domain;

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
}
