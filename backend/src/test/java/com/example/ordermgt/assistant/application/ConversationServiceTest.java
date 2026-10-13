package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service =
                new ConversationService(
                        conversationRepository,
                        pendingActionRepository,
                        new ActionCardFactory(new ToolRegistry(List.of()), new ObjectMapper()));
    }

    @Test
    void 访问不存在的会话返回未找到() {
        when(conversationRepository.findById(CONVERSATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwned(OWNER, CONVERSATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 访问他人的会话被拒绝() {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(STRANGER)));

        assertThatThrownBy(() -> service.getOwned(OWNER, CONVERSATION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 第一条客户消息成为会话标题并刷新时间() {
        Conversation conversation = untitledConversation(OWNER);
        when(conversationRepository.findById(CONVERSATION)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(conversationRepository.append(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Message message = service.appendCustomerMessage(OWNER, CONVERSATION, "  我有哪些   订单？ ");

        assertThat(conversation.getTitle()).isEqualTo("我有哪些 订单？");
        assertThat(message.role()).isEqualTo(Role.CUSTOMER);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void 记忆窗口只取最近若干条并按顺序交给模型() {
        Instant now = Instant.now();
        when(conversationRepository.findRecentMessages(CONVERSATION, 2))
                .thenReturn(
                        List.of(
                                Message.customer(CONVERSATION, "第一句", now),
                                Message.assistant(CONVERSATION, "第二句", now)));

        List<ChatMessage> memory = service.memory(CONVERSATION, 2);

        assertThat(memory)
                .extracting(ChatMessage::role)
                .containsExactly(Role.CUSTOMER, Role.ASSISTANT);
        assertThat(memory).extracting(ChatMessage::content).containsExactly("第一句", "第二句");
        verify(conversationRepository).findRecentMessages(CONVERSATION, 2);
    }

    @Test
    void 历史回填把卡片解析成它当前的状态() {
        Instant now = Instant.now();
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(OWNER)));
        when(conversationRepository.findAllMessages(CONVERSATION))
                .thenReturn(
                        List.of(
                                Message.customer(CONVERSATION, "帮我下单", now),
                                Message.assistantWithAction(
                                        CONVERSATION, "创建订单：GIZMO-03 × 3", 9L, now)));
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
        Instant now = Instant.now();
        return Conversation.rehydrate(CONVERSATION, customerId, "标题", now, now, now);
    }

    private static Conversation untitledConversation(Long customerId) {
        Instant now = Instant.now();
        return Conversation.rehydrate(CONVERSATION, customerId, null, now, now, now);
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
                "创建订单：GIZMO-03 × 3",
                status,
                now.plusSeconds(600),
                now,
                "ORD-1",
                now);
    }
}
