package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.ConversationState;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final RunRepository runRepository;
    private final AiInvocationRepository aiInvocationRepository;
    private final ActionCardFactory actionCardFactory;

    public Conversation start(Long customerId) {
        return conversationRepository.save(Conversation.start(customerId));
    }

    /**
     * The sidebar read: the customer's conversations, archived ones included only when asked for
     * (ADR-0013), each with the state derived for it on this read and the id of its live Run if it
     * has one (ADR-0011). The whole page costs two extra queries -- running Runs and live cards --
     * never one per row.
     */
    @Transactional(readOnly = true)
    public List<ConversationView> listFor(Long customerId) {
        return listFor(customerId, false);
    }

    @Transactional(readOnly = true)
    public List<ConversationView> listFor(Long customerId, boolean includeArchived) {
        List<Conversation> conversations =
                conversationRepository.findByCustomer(customerId, includeArchived);
        if (conversations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = conversations.stream().map(Conversation::getId).toList();
        Map<Long, Long> runningRuns = runRepository.runningRunIds(ids);
        Set<Long> livePending =
                pendingActionRepository.livePendingConversationIds(ids, Instant.now());
        return conversations.stream()
                .map(
                        conversation ->
                                new ConversationView(
                                        conversation,
                                        ConversationState.derive(
                                                runningRuns.containsKey(conversation.getId()),
                                                livePending.contains(conversation.getId())),
                                        runningRuns.get(conversation.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Conversation getOwned(Long customerId, Long conversationId) {
        Conversation conversation =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "conversation not found: " + conversationId));
        if (!conversation.getCustomerId().equals(customerId)) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN, "access to this conversation is forbidden");
        }
        return conversation;
    }

    /** One conversation with its derived state and live Run id, for a single-row response. */
    @Transactional(readOnly = true)
    public ConversationView view(Long customerId, Long conversationId) {
        Conversation conversation = getOwned(customerId, conversationId);
        List<Long> single = List.of(conversationId);
        Map<Long, Long> running = runRepository.runningRunIds(single);
        Set<Long> livePending =
                pendingActionRepository.livePendingConversationIds(single, Instant.now());
        return new ConversationView(
                conversation,
                ConversationState.derive(
                        running.containsKey(conversationId), livePending.contains(conversationId)),
                running.get(conversationId));
    }

    @Transactional
    public Message appendCustomerMessage(Long customerId, Long conversationId, String text) {
        Conversation conversation = getOwned(customerId, conversationId);
        Instant now = Instant.now();
        // Speaking to an archived conversation is an explicit intent to resume it, so it comes back
        // on its own -- the one write that changes visibility as a side effect (ADR-0013).
        conversation.restore();
        conversation.titleFrom(text);
        conversation.touch(now);
        conversationRepository.save(conversation);
        return conversationRepository.append(Message.customer(conversationId, text, now));
    }

    /**
     * Hides a conversation from the default list (ADR-0013). Only a quiescent conversation may be
     * archived: a Run in flight or a live confirmation card makes it non-quiescent and the request
     * a 409. Archiving is about visibility, not termination -- stopping a Run is its own action
     * (ADR-0012) -- and this rule makes "can a card in an archived conversation still be
     * confirmed?" moot: there is never a live card in one.
     */
    @Transactional
    public Conversation archive(Long customerId, Long conversationId) {
        Conversation conversation = getOwned(customerId, conversationId);
        if (!isQuiescent(conversationId)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "a conversation with a run in flight or a live card cannot be archived");
        }
        conversation.archive();
        return conversationRepository.save(conversation);
    }

    /** Restores an archived conversation to the default list. Idempotent. */
    @Transactional
    public Conversation restore(Long customerId, Long conversationId) {
        Conversation conversation = getOwned(customerId, conversationId);
        conversation.restore();
        return conversationRepository.save(conversation);
    }

    private boolean isQuiescent(Long conversationId) {
        List<Long> single = List.of(conversationId);
        return runRepository.runningConversationIds(single).isEmpty()
                && pendingActionRepository
                        .livePendingConversationIds(single, Instant.now())
                        .isEmpty();
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

    /**
     * The model calls this conversation produced, newest first -- the operations read of the rows
     * {@link AssistantService} writes. Ownership is checked exactly like {@link #history}, so a
     * stranger gets 403 rather than an outline of someone else's traffic.
     */
    @Transactional(readOnly = true)
    public List<AiInvocation> invocations(Long customerId, Long conversationId) {
        getOwned(customerId, conversationId);
        return aiInvocationRepository.findByConversationId(conversationId);
    }

    /** Records which message carried a card, so history can render it. */
    public void linkActionToMessage(PendingAction action, Long messageId) {
        action.attachToMessage(messageId);
        pendingActionRepository.save(action);
    }
}
