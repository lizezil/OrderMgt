package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.domain.RunInterruptedException;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.infrastructure.ai.tools.OrderToolDefinitions;
import com.example.ordermgt.catalog.domain.CatalogRepository;
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

    @Mock private CatalogRepository catalogRepository;

    private AssistantService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry =
                new ToolRegistry(
                        new OrderToolDefinitions()
                                .orderToolSpecs(orderService, objectMapper, catalogRepository));
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
                            assertThat(tool.resultJson())
                                    .isEqualTo("{\"total\":0,\"returned\":0,\"orders\":[]}");
                        });
        // 顾客读到的是报告，不是负载：那一行 [工具名] {JSON} 从正文里消失了（ADR-0015）。
        assertThat(result.reply()).contains("Let me look up your own orders.", "你名下还没有订单。");
        assertThat(result.reply()).doesNotContain("[queryMyOrders]", "{\"total\"");
        verify(orderService).listByCustomer(CUSTOMER, 1, 20);
        verify(pendingActionRepository, never()).save(any());
    }

    @Test
    void aReadonlyTurnWithNoModelProseStillAnswersTheCustomer() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 实测过的失败形态：模型把补全 token 全花在工具调用上，一个字都没写。回复不能因此只剩
        // 一坨负载——报告自带引导句，没有正文也读得通。
        AssistantResult result =
                service.complete(
                        context(),
                        AssistantTurn.of(
                                "",
                                List.of(new ProposedToolCall("queryMyOrders", "{}", false)),
                                AiUsage.EMPTY));

        assertThat(result.reply()).isEqualTo("你名下还没有订单。");
    }

    /**
     * 模型给的只读参数服务端跑不成（ADR-0016），而且它一个字都没吐：把拒绝当观察回喂、重问一次。顾客从没看见第一次调用， 所以它留下一行失败，重问的那次另记一行——一次调用永远一行。
     */
    @Test
    void illegalReadonlyArgumentsAreFedBackToTheModelAndReAskedOnce() {
        givenANewConversation();
        when(orderService.listByCustomer(CUSTOMER, 1, 1))
                .thenReturn(PageResult.of(List.of(), 0, 1, 1));
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList()))
                .thenReturn(limitTurn("", "{\"limit\":5000}"), limitTurn("", "{\"limit\":1}"));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result = service.chat(CUSTOMER, null, "查询最近一次下单");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> memories = ArgumentCaptor.forClass(List.class);
        verify(aiAssistantPort, times(2)).respond(eq(CUSTOMER), any(), memories.capture());
        List<ChatMessage> first = memories.getAllValues().get(0);
        List<ChatMessage> second = memories.getAllValues().get(1);
        assertThat(second).hasSize(first.size() + 1);
        ChatMessage observation = second.get(second.size() - 1);
        // 观察说的是被拒的那一次：哪个工具、什么参数、服务器为什么跑不成。模型看不见自己刚才提议过什么。
        assertThat(observation.role()).isEqualTo(Role.TOOL);
        assertThat(observation.content())
                .contains("queryMyOrders")
                .contains("limit")
                .contains("5000");
        // 这条观察只活在这次重问里：顾客没说过它，历史里也不该有服务器的一句报错。
        verify(conversationService, never()).appendCustomerMessage(any(), any(), contains("5000"));

        assertThat(result.reply()).isEqualTo("你名下还没有订单。");
        ArgumentCaptor<AiInvocation> audits = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository, times(2)).save(audits.capture());
        assertThat(audits.getAllValues().get(0).outcome()).isEqualTo(AiInvocationOutcome.ERROR);
        assertThat(audits.getAllValues().get(0).error()).contains("limit");
        assertThat(audits.getAllValues().get(1).outcome()).isEqualTo(AiInvocationOutcome.OK);
    }

    /** 模型已经开口了就不重问：流式那边顾客已经看见半截话，再来一段开场白比按错参数更糟。这一轮改用安全参数收尾， 并在回复里声明——降级不能是静悄悄的。 */
    @Test
    void aModelThatAlreadySpokeIsRepairedInsteadOfReAsked() {
        givenANewConversation();
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList()))
                .thenReturn(limitTurn("我看看你最近的一单。", "{\"limit\":5000}"));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result = service.chat(CUSTOMER, null, "查询最近一次下单");

        verify(aiAssistantPort).respond(eq(CUSTOMER), any(), anyList());
        assertThat(result.reply())
                .startsWith(AssistantService.SAFE_DEFAULT_NOTE)
                .contains("我看看你最近的一单。")
                .contains("你名下还没有订单。");
        // 一次调用一行审计：这一次的回复照样落地，所以它是 OK，而不是再补一条失败。
        ArgumentCaptor<AiInvocation> audits = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(audits.capture());
        assertThat(audits.getValue().outcome()).isEqualTo(AiInvocationOutcome.OK);
    }

    /** 回喂之后仍然非法：不再问第三次。有界重问的意思是"最多一次"，不是"直到模型听话"。 */
    @Test
    void theReAskIsBoundedAndTheSecondRefusalIsRepairedNotRetried() {
        givenANewConversation();
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList()))
                .thenReturn(
                        limitTurn("", "{\"limit\":5000}"), limitTurn("", "{\"limit\":\"three\"}"));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssistantResult result = service.chat(CUSTOMER, null, "查询最近一次下单");

        verify(aiAssistantPort, times(2)).respond(eq(CUSTOMER), any(), anyList());
        assertThat(result.reply())
                .startsWith(AssistantService.SAFE_DEFAULT_NOTE)
                .isEqualTo(AssistantService.SAFE_DEFAULT_NOTE + "\n\n你名下还没有订单。");
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
                memory -> new AssistantTurn("hello", List.of(), AiUsage.EMPTY, "qwen-max", 5));

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
                                        memory -> {
                                            throw boom;
                                        }))
                .isSameAs(boom);

        verify(aiInvocationRepository).save(any());
        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.getAllValues().get(1).error()).isEqualTo("上游模型超时");
        assertThat(runs.getAllValues().get(1).endedAt()).isNotNull();
    }

    /**
     * 客户点了停止（ADR-0012）：半截文本作为一条普通 ASSISTANT 消息落库，Run 收 CANCELLED，审计记 CANCELLED， 异常继续往上抛好让流式端点以
     * cancelled 帧收尾——这不是失败，不需要 error。
     */
    @Test
    void aStoppedStreamingTurnKeepsTheHalfTextAndClosesTheRunCancelled() {
        AssistantTurn partial = AssistantTurn.of("我准备好", List.of(), AiUsage.EMPTY);
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        run -> {},
                                        memory -> {
                                            throw new RunCancelledException(partial);
                                        }))
                .isInstanceOf(RunCancelledException.class);

        verify(conversationService).appendAssistantMessage(CONVERSATION, "我准备好");
        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(0).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runs.getAllValues().get(1).endedAt()).isNotNull();
        ArgumentCaptor<AiInvocation> audits = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(audits.capture());
        assertThat(audits.getValue().outcome()).isEqualTo(AiInvocationOutcome.CANCELLED);
    }

    /**
     * 上游在吐字之后断掉（不是客户停的）：已经看过的话照样落库，但标注[生成中断]，Run 收 INTERRUPTED，审计记 ERROR；异常继续往上抛，流式端点以 error
     * 帧收尾——前端的重试因此看得到 之前说过的话，而不是一段凭空消失的回答。
     */
    @Test
    void anUpstreamInterruptionKeepsTheHalfTextAnnotatedAndClosesTheRunInterrupted() {
        AssistantTurn partial = AssistantTurn.of("你有 3 张订单", List.of(), AiUsage.EMPTY);
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        run -> {},
                                        memory -> {
                                            throw new RunInterruptedException(
                                                    partial,
                                                    new IllegalStateException(
                                                            "upstream went away"));
                                        }))
                .isInstanceOf(RunInterruptedException.class);

        verify(conversationService).appendAssistantMessage(CONVERSATION, "你有 3 张订单\n\n[生成中断]");
        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(runs.getAllValues().get(1).endedAt()).isNotNull();
        ArgumentCaptor<AiInvocation> audits = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationRepository).save(audits.capture());
        assertThat(audits.getValue().outcome()).isEqualTo(AiInvocationOutcome.ERROR);
    }

    /** 停止发生在模型开口之前：没有半截可留，就不落一条空的助手消息，但 Run 与审计照旧。 */
    @Test
    void aStopBeforeAnyProseLeavesNoEmptyAssistantMessage() {
        ArgumentCaptor<Run> runs = ArgumentCaptor.forClass(Run.class);

        assertThatThrownBy(
                        () ->
                                service.executeTurn(
                                        context(),
                                        run -> {},
                                        memory -> {
                                            throw new RunCancelledException(null);
                                        }))
                .isInstanceOf(RunCancelledException.class);

        verify(conversationService, never()).appendAssistantMessage(any(), anyString());
        verify(runRepository, times(2)).save(runs.capture());
        assertThat(runs.getAllValues().get(1).status()).isEqualTo(RunStatus.CANCELLED);
        verify(aiInvocationRepository).save(any());
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

        assertThatThrownBy(() -> service.executeTurn(context(), memory -> answered))
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
                                        memory -> {
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
                                        memory ->
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
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList())).thenThrow(timeout);
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
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList())).thenReturn(answered);
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
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList()))
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
        when(aiAssistantPort.respond(eq(CUSTOMER), any(), anyList()))
                .thenReturn(AssistantTurn.of("ok", List.of(), AiUsage.EMPTY));
        when(aiInvocationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.chat(CUSTOMER, null, "first thread");
        service.chat(CUSTOMER, null, "second thread");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> memories = ArgumentCaptor.forClass(List.class);
        verify(aiAssistantPort, times(2)).respond(eq(CUSTOMER), any(), memories.capture());
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

    /** {@code chat()} 起步的那三步：新会话、顾客的话落库、取本轮记忆。 */
    private void givenANewConversation() {
        when(conversationService.start(CUSTOMER)).thenReturn(conversation());
        when(conversationService.appendCustomerMessage(eq(CUSTOMER), eq(CONVERSATION), anyString()))
                .thenReturn(Message.customer(CONVERSATION, "查询最近一次下单", Instant.now()));
        when(conversationService.memory(CONVERSATION, MEMORY_WINDOW))
                .thenReturn(List.of(new ChatMessage(Role.CUSTOMER, "查询最近一次下单")));
    }

    /** 一轮"只查最近几单"的提议：文字可空，参数由用例给——空文字正是实测过的那种失败。 */
    private static AssistantTurn limitTurn(String text, String argumentsJson) {
        return AssistantTurn.of(
                text,
                List.of(new ProposedToolCall("queryMyOrders", argumentsJson, false)),
                AiUsage.EMPTY);
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
