package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.infrastructure.ai.tools.OrderToolDefinitions;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Business rule: 一轮对话的编排——只读当场执行、变更只提议、白名单外一律拒绝，审计只做旁路。
 *
 * <p>这是"AI 提议、人确认、服务端校验"的中枢：模型说什么都不重要，重要的是服务端把哪些话变成了 已执行的查询、哪些变成了等确认的卡片，以及模型失败时客户看到什么、运维能不能查到。
 */
@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long CONVERSATION = 5L;
    private static final int MEMORY_WINDOW = 20;

    @Mock private AiAssistantPort aiAssistantPort;

    @Mock private ConversationService conversationService;

    @Mock private PendingActionRepository pendingActionRepository;

    @Mock private AiInvocationRepository aiInvocationRepository;

    @Mock private OrderService orderService;

    private AssistantService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry =
                new ToolRegistry(
                        new OrderToolDefinitions().orderToolSpecs(orderService, objectMapper));
        service =
                new AssistantService(
                        aiAssistantPort,
                        registry,
                        conversationService,
                        pendingActionRepository,
                        new ActionCardFactory(registry, objectMapper),
                        aiInvocationRepository,
                        objectMapper,
                        MEMORY_WINDOW,
                        15);
    }

    @Test
    void 变更工具只落成待确认卡片绝不执行() {
        when(pendingActionRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PendingAction action = invocation.getArgument(0);
                            action.assignId(77L);
                            return action;
                        });
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString()))
                .thenReturn(Message.assistant(CONVERSATION, "我准备好了一个下单操作。", Instant.now()));
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString(), any()))
                .thenReturn(Message.assistantWithAction(CONVERSATION, "创建订单", 77L, Instant.now()));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result = service.complete(context(), createOrderTurn());

        assertThat(result.actions())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.status()).isEqualTo("PENDING");
                            assertThat(card.title()).isEqualTo("创建订单");
                            assertThat(card.summary()).isEqualTo("创建订单：GIZMO-03 × 3");
                        });
        assertThat(result.executed()).isEmpty();
        // 关掉自动执行的闸口：助手这一侧根本没有通往变更 handler 的路径。
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void 只读工具当场执行并把结果带进回复() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result =
                service.complete(
                        context(),
                        AssistantTurn.of(
                                "我先查一下你自己的订单。",
                                List.of(new ProposedToolCall("queryMyOrders", "{}", false)),
                                AiUsage.EMPTY));

        assertThat(result.executed())
                .singleElement()
                .satisfies(
                        tool -> {
                            assertThat(tool.name()).isEqualTo("queryMyOrders");
                            assertThat(tool.resultJson()).isEqualTo("[]");
                        });
        assertThat(result.reply()).contains("[queryMyOrders]");
        verify(orderService).listByCustomer(CUSTOMER, 1, 20);
        verify(pendingActionRepository, never()).save(any());
    }

    @Test
    void 模型提出未注册的工具时整轮被拒() {
        AssistantTurn turn =
                AssistantTurn.of(
                        "",
                        List.of(new ProposedToolCall("dropDatabase", "{}", true)),
                        AiUsage.EMPTY);

        assertThatThrownBy(() -> service.complete(context(), turn))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_AI_ERROR);
        verify(pendingActionRepository, never()).save(any());
    }

    @Test
    void 参数无法描述的提议仍然落成一张可拒绝的卡片而不是把整轮打崩() {
        when(pendingActionRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PendingAction action = invocation.getArgument(0);
                            action.assignId(88L);
                            return action;
                        });
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString(), any()))
                .thenReturn(Message.assistantWithAction(CONVERSATION, "创建订单", 88L, Instant.now()));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantTurn turn =
                AssistantTurn.of(
                        "",
                        List.of(new ProposedToolCall("createOrder", "{\"lines\":[]}", true)),
                        AiUsage.EMPTY);

        AssistantResult result = service.complete(context(), turn);

        assertThat(result.actions())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.status()).isEqualTo("PENDING");
                            // 描述不出来就降级成工具名，客户依然能拒绝这张卡——而不是 500。
                            assertThat(card.summary()).isEqualTo("createOrder");
                        });
        // 参数不合法只是"这张卡不能执行"，变更 handler 自始至终没被触碰。
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void 一次成功的模型调用留下带模型与耗时的审计行() {
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(
                context(),
                new AssistantTurn("你好", List.of(), new AiUsage(10, 20), "qwen-max", 1234));

        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(AiInvocationOutcome.OK);
        assertThat(captor.getValue().model()).isEqualTo("qwen-max");
        assertThat(captor.getValue().latencyMs()).isEqualTo(1234);
        assertThat(captor.getValue().conversationId()).isEqualTo(CONVERSATION);
    }

    @Test
    void 审计写不进去也不能让这一轮回复失败() {
        when(aiInvocationRepository.save(any()))
                .thenThrow(new RuntimeException("audit store down"));

        AssistantResult result =
                service.complete(
                        context(),
                        new AssistantTurn("你好", List.of(), AiUsage.EMPTY, "qwen-max", 5));

        assertThat(result.reply()).isEqualTo("你好");
    }

    @Test
    void 模型调用失败时抛出并把失败记进审计() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "你好", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW)).thenReturn(List.of());
        AiUpstreamException timeout =
                new AiUpstreamException(
                        ErrorCode.UPSTREAM_AI_ERROR,
                        "AI 服务调用失败",
                        new RuntimeException("read timeout"),
                        "qwen-max",
                        60000,
                        AiInvocationOutcome.TIMEOUT);
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList())).thenThrow(timeout);
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.chat(CUSTOMER, null, "你好")).isSameAs(timeout);

        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(AiInvocationOutcome.TIMEOUT);
        assertThat(captor.getValue().model()).isEqualTo("qwen-max");
    }

    @Test
    void 交给模型的记忆窗口取配置值() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "你好", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW)).thenReturn(List.of());
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList()))
                .thenReturn(AssistantTurn.of("你好", List.of(), AiUsage.EMPTY));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.chat(CUSTOMER, null, "你好");

        verify(conversationService).memory(CONVERSATION, MEMORY_WINDOW);
    }

    private static AssistantService.TurnContext context() {
        return new AssistantService.TurnContext(
                conversation(), List.of(new ChatMessage(null, "帮我下单")));
    }

    private static AssistantTurn createOrderTurn() {
        return AssistantTurn.of(
                "我准备好了一个下单操作。",
                List.of(
                        new ProposedToolCall(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}",
                                true)),
                AiUsage.EMPTY);
    }

    private static Conversation conversation() {
        Instant now = Instant.now();
        return Conversation.rehydrate(CONVERSATION, CUSTOMER, "标题", now, now, now);
    }
}
