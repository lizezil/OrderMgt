package com.example.ordermgt.assistant.domain;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(Long id);

    List<Conversation> findByCustomer(Long customerId);

    Message append(Message message);

    /** The last {@code limit} messages in chronological order (oldest first). */
    List<Message> findRecentMessages(Long conversationId, int limit);

    /** Full history in chronological order, for the frontend to backfill. */
    List<Message> findAllMessages(Long conversationId);
}
