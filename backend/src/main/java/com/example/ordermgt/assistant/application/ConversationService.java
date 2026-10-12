package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversations are the boundary of memory: "new conversation" simply starts one, and a model call
 * only ever sees messages from a single conversation.
 *
 * <p>Every ownership check lives here, so no caller can read or append to a conversation that
 * belongs to someone else.
 */
@RequiredArgsConstructor
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final PendingActionRepository pendingActionRepository;
    private final ActionCardFactory actionCardFactory;

    @Transactional
    public Conversation start(Long customerId) {
        return conversationRepository.save(Conversation.start(customerId));
    }

    @Transactional(readOnly = true)
    public List<Conversation> listFor(Long customerId) {
        return conversationRepository.findByCustomer(customerId);
    }

    @Transactional(readOnly = true)
    public Conversation getOwned(Long customerId, Long conversationId) {
        Conversation conversation =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "对话不存在: " + conversationId));
        if (!conversation.getCustomerId().equals(customerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该对话");
        }
        return conversation;
    }

    @Transactional
    public Message appendCustomerMessage(Long customerId, Long conversationId, String text) {
        Conversation conversation = getOwned(customerId, conversationId);
        Instant now = Instant.now();
        conversation.titleFrom(text);
        conversation.touch(now);
        conversationRepository.save(conversation);
        return conversationRepository.append(Message.customer(conversationId, text, now));
    }

    @Transactional
    public Message appendAssistantMessage(Long conversationId, String text) {
        return appendAssistantMessage(conversationId, text, null);
    }

    @Transactional
    public Message appendAssistantMessage(Long conversationId, String text, Long pendingActionId) {
        Instant now = Instant.now();
        conversationRepository
                .findById(conversationId)
                .ifPresent(
                        conversation -> {
                            conversation.touch(now);
                            conversationRepository.save(conversation);
                        });
        return conversationRepository.append(
                Message.assistantWithAction(conversationId, text, pendingActionId, now));
    }

    /** The window of memory handed to the model: the last {@code window} turns. */
    @Transactional(readOnly = true)
    public List<ChatMessage> memory(Long conversationId, int window) {
        return conversationRepository.findRecentMessages(conversationId, window).stream()
                .map(ChatMessage::of)
                .toList();
    }

    /** Full history, with each message's card (if it had one) resolved to its current state. */
    @Transactional(readOnly = true)
    public List<MessageView> history(Long customerId, Long conversationId) {
        getOwned(customerId, conversationId);
        List<Message> messages = conversationRepository.findAllMessages(conversationId);

        List<Long> actionIds =
                messages.stream()
                        .map(Message::pendingActionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, ActionCard> cards =
                pendingActionRepository.findByIdIn(actionIds).stream()
                        .collect(
                                Collectors.toMap(
                                        PendingAction::getId,
                                        actionCardFactory::card,
                                        (a, b) -> a));

        return messages.stream()
                .map(message -> new MessageView(message, cards.get(message.pendingActionId())))
                .toList();
    }

    /** Records which message carried a card, so history can render it. */
    @Transactional
    public void linkActionToMessage(PendingAction action, Long messageId) {
        action.attachToMessage(messageId);
        pendingActionRepository.save(action);
    }
}
