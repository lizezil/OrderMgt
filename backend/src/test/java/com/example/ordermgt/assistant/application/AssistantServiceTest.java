package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
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

    @Mock private RunRepository runRepository;

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
                        runRepository,
                        new ActionCardFactory(registry, objectMapper),
                        aiInvocationRepository,
                        objectMapper,
                        MEMORY_WINDOW,
                        15);
        // Run rows are the seam under test, not the thing under test in most cases: echo back what
        // is saved so executeTurn can finish the run it opened. lenient() because the tests that
        // only exercise complete() never reach it.
        lenient()
                .when(runRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void mutatingToolsOnlyBecomePendingCardsNeverExecute() {
        when(pendingActionRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PendingAction action = invocation.getArgument(0);
                            action.assignId(77L);
                            return action;
                        });
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString()))
                .thenReturn(
                        Message.assistant(
                                CONVERSATION,
                                "I have prepared an order for you, please confirm.",
                                Instant.now()));
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString(), any()))
                .thenReturn(
                        Message.assistantWithAction(
                                CONVERSATION, "Place Order", 77L, Instant.now()));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result = service.complete(context(), createOrderTurn());

        assertThat(result.actions())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.status()).isEqualTo("PENDING");
                            assertThat(card.title()).isEqualTo("Create Order");
                            assertThat(card.summary()).isEqualTo("Place order: GIZMO-03 × 3");
                        });
        assertThat(result.executed()).isEmpty();
        // 关掉自动执行的闸口：助手这一侧根本没有通往变更 handler 的路径。
        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void readonlyToolsExecuteOnTheSpotAndCarryResultsIntoTheReply() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result =
                service.complete(
                        context(),
                        AssistantTurn.of(
                                "Let me look up your own orders.",
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
    void proposingUnregisteredToolRejectsTheWholeTurn() {
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
    void undescribableProposalStillBecomesRejectableCardNotACrash() {
        when(pendingActionRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PendingAction action = invocation.getArgument(0);
                            action.assignId(88L);
                            return action;
                        });
        when(conversationService.appendAssistantMessage(eq(CONVERSATION), anyString(), any()))
                .thenReturn(
                        Message.assistantWithAction(
                                CONVERSATION, "Place Order", 88L, Instant.now()));
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
    void successfulModelCallLeavesAuditRowWithModelAndLatency() {
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(
                context(),
                new AssistantTurn("hello", List.of(), new AiUsage(10, 20), "qwen-max", 1234));

        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(AiInvocationOutcome.OK);
        assertThat(captor.getValue().model()).isEqualTo("qwen-max");
        assertThat(captor.getValue().latencyMs()).isEqualTo(1234);
        assertThat(captor.getValue().conversationId()).isEqualTo(CONVERSATION);
    }

    @Test
    void auditWriteFailureMustNotFailTheTurn() {
        when(aiInvocationRepository.save(any()))
                .thenThrow(new RuntimeException("audit store down"));

        AssistantResult result =
                service.complete(
                        context(),
                        new AssistantTurn("hello", List.of(), AiUsage.EMPTY, "qwen-max", 5));

        assertThat(result.reply()).isEqualTo("hello");
    }

    /** 两个发送入口共用的一条 seam：一次回合开一个 Run，成功收 COMPLETED。 */
    @Test
    void aTurnOpensARunningRunAndClosesItCompleted() {
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        service.executeTurn(
                context(),
                () -> new AssistantTurn("hello", List.of(), AiUsage.EMPTY, "qwen-max", 5));

        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(0).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(runs.getAllValues().get(0).endedAt()).isNull();
        assertThat(runs.getAllValues().get(0).conversationId()).isEqualTo(CONVERSATION);
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runs.getAllValues().get(1).endedAt()).isNotNull();
    }

    /** 模型失败：Run 收 FAILED 并带上原因，审计照记——有问无答的那一行由此可查。 */
    @Test
    void aFailedModelCallClosesTheRunFailedWithTheReason() {
        RuntimeException boom = new IllegalStateException("上游模型超时");
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        () -> {
                                            throw boom;
                                        }))
                .isSameAs(boom);

        verify(aiInvocationRepository).save(any());
        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.getAllValues().get(1).error()).isEqualTo("上游模型超时");
        assertThat(runs.getAllValues().get(1).endedAt()).isNotNull();
    }

    /** 模型答了但回合收不了尾（白名单外工具）：Run 一样收 FAILED，原因落在行上。 */
    @Test
    void aCompletionFailureStillClosesTheRunFailed() {
        AssistantTurn answered =
                new AssistantTurn(
                        "",
                        List.of(new ProposedToolCall("dropDatabase", "{}", true)),
                        new AiUsage(11, 22),
                        "qwen-max",
                        4242);
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(() -> service.executeTurn(context(), () -> answered))
                .isInstanceOf(BusinessException.class);

        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.getAllValues().get(1).error()).contains("dropDatabase");
    }

    /** 失败没有 message 时，Run 也要有一个理由——退回异常自己的名字，绝不留空。 */
    @Test
    void aFailureWithoutAMessageStillGivesTheRunAReason() {
        RuntimeException silent = new IllegalStateException();
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        () -> {
                                            throw silent;
                                        }))
                .isSameAs(silent);

        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).error()).isEqualTo("IllegalStateException");
    }

    /** 收尾写 Run 失败不等于回合失败：已经落库的回复不能被补记一条 ERROR 审计。 */
    @Test
    void aFailureToCloseTheRunIsNotAuditedAsATurnFailure() {
        when(runRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0)) // the RUNNING insert succeeds
                .thenThrow(new RuntimeException("runs table down")); // closing it does not

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        () ->
                                                new AssistantTurn(
                                                        "hello",
                                                        List.of(),
                                                        AiUsage.EMPTY,
                                                        "qwen-max",
                                                        5)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("runs table down");

        ArgumentCaptor<AiInvocation> audits = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(audits.capture());
        assertThat(audits.getValue().outcome()).isEqualTo(AiInvocationOutcome.OK);
    }

    @Test
    void modelCallFailureIsRethrownAndRecordedInAudit() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "hello", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW)).thenReturn(List.of());
        AiUpstreamException timeout =
                new AiUpstreamException(
                        ErrorCode.UPSTREAM_AI_ERROR,
                        "AI upstream call failed",
                        new RuntimeException("read timeout"),
                        "qwen-max",
                        60000,
                        AiInvocationOutcome.TIMEOUT);
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList())).thenThrow(timeout);
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.chat(CUSTOMER, null, "hello")).isSameAs(timeout);

        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(AiInvocationOutcome.TIMEOUT);
        assertThat(captor.getValue().model()).isEqualTo("qwen-max");
    }

    @Test
    void modelAnsweredButCompletionFailedStillLeavesAuditRow() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "hello", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW)).thenReturn(List.of());
        // 模型点了一个白名单外的工具：complete() 会拒绝，但这次调用已经发生、也计了费
        AssistantTurn answered =
                new AssistantTurn(
                        "",
                        List.of(new ProposedToolCall("deleteEverything", "{}", true)),
                        new AiUsage(11, 22),
                        "qwen-max",
                        4242);
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList())).thenReturn(answered);
        when(aiInvocationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.chat(CUSTOMER, null, "hello"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(captor.capture());
        AiInvocation row = captor.getValue();
        assertThat(row.outcome()).isEqualTo(AiInvocationOutcome.ERROR);
        assertThat(row.model()).isEqualTo("qwen-max");
        assertThat(row.latencyMs()).isEqualTo(4242);
        assertThat(row.promptTokens()).isEqualTo(11);
        assertThat(row.error()).contains("deleteEverything");
    }

    @Test
    void memoryWindowGivenToModelUsesConfiguredValue() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "hello", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW)).thenReturn(List.of());
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList()))
                .thenReturn(AssistantTurn.of("hello", List.of(), AiUsage.EMPTY));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.chat(CUSTOMER, null, "hello");

        verify(conversationService).memory(CONVERSATION, MEMORY_WINDOW);
    }

    /** 记忆的边界是会话本身："新对话"意味着模型看不到旧会话说过什么。两次对话各取各的记忆，绝不串味。 */
    @Test
    void memoryHandedToTheModelNeverCrossesConversations() {
        Instant now = Instant.now();
        Conversation first =
                Conversation.rehydrate(
                        5L, CUSTOMER, "first", ConversationStatus.ACTIVE, now, now, now);
        Conversation second =
                Conversation.rehydrate(
                        6L, CUSTOMER, "second", ConversationStatus.ACTIVE, now, now, now);
        when(conversationService.start(CUSTOMER)).thenReturn(first, second);
        when(conversationService.memory(5L, MEMORY_WINDOW))
                .thenReturn(List.of(new ChatMessage(Role.CUSTOMER, "first thread")));
        when(conversationService.memory(6L, MEMORY_WINDOW))
                .thenReturn(List.of(new ChatMessage(Role.CUSTOMER, "second thread")));
        when(aiAssistantPort.respond(eq(CUSTOMER), anyList()))
                .thenReturn(AssistantTurn.of("ok", List.of(), AiUsage.EMPTY));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.chat(CUSTOMER, null, "first thread");
        service.chat(CUSTOMER, null, "second thread");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> memories = ArgumentCaptor.forClass(List.class);
        verify(aiAssistantPort, times(2)).respond(eq(CUSTOMER), memories.capture());
        assertThat(memories.getAllValues().get(0))
                .extracting(ChatMessage::content)
                .containsExactly("first thread");
        // 第二段对话的模型调用只该看到第二段自己的话——第一段的一个字都不能漏进来。
        assertThat(memories.getAllValues().get(1))
                .extracting(ChatMessage::content)
                .containsExactly("second thread");
    }

    private static AssistantService.TurnContext context() {
        return new AssistantService.TurnContext(
                conversation(), List.of(new ChatMessage(null, "help me order")));
    }

    private static AssistantTurn createOrderTurn() {
        return AssistantTurn.of(
                "I have prepared an order for you, please confirm.",
                List.of(
                        new ProposedToolCall(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}",
                                true)),
                AiUsage.EMPTY);
    }

    private static Conversation conversation() {
        Instant now = Instant.now();
        return Conversation.rehydrate(
                CONVERSATION, CUSTOMER, "title", ConversationStatus.ACTIVE, now, now, now);
    }
}
