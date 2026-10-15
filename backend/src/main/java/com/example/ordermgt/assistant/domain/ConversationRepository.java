package com.example.ordermgt.assistant.domain;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(Long id);

    /**
     * A customer's conversations, newest activity first. Archived ones are excluded unless {@code
     * includeArchived} (ADR-0013) -- the default sidebar hides them.
     */
    List<Conversation> findByCustomer(Long customerId, boolean includeArchived);

    Message append(Message message);

    /** The last {@code limit} messages in chronological order (oldest first). */
    List<Message> findRecentMessages(Long conversationId, int limit);

    /**
     * One segment of history: up to {@code limit} messages strictly older than {@code beforeId} --
     * the newest {@code limit} when {@code beforeId} is null -- in chronological order (US-2.7).
     * The cursor is an id, never an offset, so a message arriving mid-thread cannot shift the
     * window and make a page repeat or skip a line.
     */
    List<Message> findMessagesBefore(Long conversationId, Long beforeId, int limit);

    /** The conversation's newest message, or empty when it has none (the retry path's question). */
    Optional<Message> findLastMessage(Long conversationId);
}
