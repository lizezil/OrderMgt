package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.ConversationState;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Business rule: 会话是记忆的边界，也是归属的边界。
 *
 * <p>一位客户不能读写别人的会话；喂给模型的记忆只取本会话最近若干条；回填历史时，曾出现过卡片的 那一轮要把卡片连同它**当前**的状态一起还回来，而不是一段死文字。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final Long OWNER = 1001L;
    private static final Long STRANGER = 2002L;
    private static final Long CONVERSATION = 5L;

    @Mock private ConversationRepository conversationRepository;

    @Mock private PendingActionRepository pendingActionRepository;

    @Mock private RunRepository runRepository;

    @Mock private AiInvocationRepository aiInvocationRepository;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service =
                new ConversationService(
                        conversationRepository,
                        pendingActionRepository,
                        runRepository,
                        aiInvocationRepository,
                        new ActionCardFactory(new ToolRegistry(List.of()), new ObjectMapper()));
    }

    /** 侧栏三值全靠派生：有活 Run 就 RUNNING（压过待确认），否则有未过期卡片就 WAITING_APPROVAL，否则 IDLE。 */
    @Test
    void sidebarStateIsDerivedFromRunsThenLiveCardsThenIdle() {
        when(conversationRepository.findByCustomer(OWNER, false))
                .thenReturn(
                        List.of(
                                conversation(OWNER, 5L),
                                conversation(OWNER, 6L),
                                conversation(OWNER, 7L)));
        when(runRepository.runningRunIds(any())).thenReturn(java.util.Map.of(5L, 500L));
        when(pendingActionRepository.livePendingConversationIds(any(), any()))
                .thenReturn(Set.of(5L, 6L));

        List<ConversationView> views = service.listFor(OWNER);

        assertThat(views)
                .extracting(ConversationView::state)
                .containsExactly(
                        ConversationState.RUNNING,
                        ConversationState.WAITING_APPROVAL,
                        ConversationState.IDLE);
    }

    @Test
    void anEmptySidebarCostsNoStateQueries() {
        when(conversationRepository.findByCustomer(OWNER, false)).thenReturn(List.of());

        assertThat(service.listFor(OWNER)).isEmpty();
        verifyNoInteractions(runRepository, pendingActionRepository);
    }

    @Test
    void accessingNonexistentConversationReturnsNotFound() {
        when(conversationRepository.findById(CONVERSATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwned(OWNER, CONVERSATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void accessingSomeoneElsesConversationIsRejected() {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(STRANGER)));

        assertThatThrownBy(() -> service.getOwned(OWNER, CONVERSATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void theAuditReadsOnlyTheOwnConversationModelCalls() {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(OWNER)));
        AiInvocation invocation =
                AiInvocation.record(
                        CONVERSATION,
                        OWNER,
                        "qwen-max",
                        12,
                        34,
                        4242,
                        AiInvocationOutcome.OK,
                        null,
                        null);
        when(aiInvocationRepository.findByConversationId(CONVERSATION))
                .thenReturn(List.of(invocation));

        assertThat(service.invocations(OWNER, CONVERSATION)).containsExactly(invocation);
    }

    @Test
    void aStrangerCannotReadTheAuditTrail() {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(STRANGER)));

        assertThatThrownBy(() -> service.invocations(OWNER, CONVERSATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void firstCustomerMessageBecomesTitleAndRefreshesTimestamp() {
        Conversation conversation = untitledConversation(OWNER);
        when(conversationRepository.findById(CONVERSATION)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(conversationRepository.append(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Message message =
                service.appendCustomerMessage(OWNER, CONVERSATION, "  what   orders do I have? ");

        assertThat(conversation.getTitle()).isEqualTo("what orders do I have?");
        assertThat(message.role()).isEqualTo(Role.CUSTOMER);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void memoryWindowTakesOnlyRecentMessagesInOrder() {
        Instant now = Instant.now();
        when(conversationRepository.findRecentMessages(CONVERSATION, 2))
                .thenReturn(
                        List.of(
                                Message.customer(CONVERSATION, "first sentence", now),
                                Message.assistant(CONVERSATION, "second sentence", now)));

        List<ChatMessage> memory = service.memory(CONVERSATION, 2);

        assertThat(memory)
                .extracting(ChatMessage::role)
                .containsExactly(Role.CUSTOMER, Role.ASSISTANT);
        assertThat(memory)
                .extracting(ChatMessage::content)
                .containsExactly("first sentence", "second sentence");
        verify(conversationRepository).findRecentMessages(CONVERSATION, 2);
    }

    @Test
    void historyResolvesCardToItsCurrentState() {
        Instant now = Instant.now();
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(OWNER)));
        when(conversationRepository.findAllMessages(CONVERSATION))
                .thenReturn(
                        List.of(
                                Message.customer(CONVERSATION, "help me order", now),
                                Message.assistantWithAction(
                                        CONVERSATION, "Place order: GIZMO-03 × 3", 9L, now)));
        when(pendingActionRepository.findByIdIn(List.of(9L)))
                .thenReturn(List.of(pendingAction(PendingActionStatus.CONFIRMED)));

        List<MessageView> history = service.history(OWNER, CONVERSATION);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).action()).isNull();
        assertThat(history.get(1).action()).isNotNull();
        assertThat(history.get(1).action().status()).isEqualTo("CONFIRMED");
        assertThat(history.get(1).action().token()).isEqualTo("tok");
    }

    private static Conversation conversation(Long customerId) {
        return conversation(customerId, CONVERSATION);
    }

    private static Conversation conversation(Long customerId, Long id) {
        Instant now = Instant.now();
        return Conversation.rehydrate(
                id, customerId, "title", ConversationStatus.ACTIVE, now, now, now);
    }

    private static Conversation untitledConversation(Long customerId) {
        Instant now = Instant.now();
        return Conversation.rehydrate(
                CONVERSATION, customerId, null, ConversationStatus.ACTIVE, now, now, now);
    }

    private static PendingAction pendingAction(PendingActionStatus status) {
        Instant now = Instant.now();
        return PendingAction.rehydrate(
                9L,
                "tok",
                OWNER,
                CONVERSATION,
                null,
                "createOrder",
                "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}",
                "Place order: GIZMO-03 × 3",
                status,
                now.plusSeconds(600),
                now,
                "ORD-1",
                now);
    }
}
