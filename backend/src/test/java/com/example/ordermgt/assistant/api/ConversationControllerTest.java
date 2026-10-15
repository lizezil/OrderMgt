package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.CustomerRateLimiter;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Business rule: 断线是投递的事，不是回合的事——SSE 连接半路死掉（关页面、网络抖动）时，模型照常跑完、 这一轮照常落库：客户消息在 prepare
 * 里已经入库，助手的回复再跟着丢就成了有问无答。"落库 + 审计 + Run 收尾"现在归 {@code executeTurn} 一处负责，所以这一层只钉一件事：断线后控制器仍把这一轮交给
 * executeTurn 走完，而不是半途松手。
 *
 * <p>反向契约同样钉住：模型真失败时，error 事件照旧上线，且失败审计不由这一层重复记一遍。
 *
 * <p>{@code streamTurn} 包可见是为了不启容器直接测这条边界（同 {@code SpringAiAdapter#retryBeforeFirstToken} 的手法）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long CONVERSATION = 7L;
    private static final String QUESTION = "我有哪些订单？";

    @Mock private ConversationService conversationService;
    @Mock private AssistantService assistantService;
    @Mock private ChatSender chatSender;
    @Mock private ConversationRunQueue runQueue;
    @Mock private RunRegistry runRegistry;
    @Mock private RunService runService;
    @Mock private ConversationEventPublisher events;
    @Mock private AiAssistantPort aiAssistantPort;
    @Mock private AssistantApiMapper mapper;
    @Mock private SseEmitter emitter;

    private ConversationController controller;
    private AssistantService.TurnContext context;
    private AssistantTurn turn;
    private AssistantResult result;

    @BeforeEach
    void setUp() {
        controller =
                new ConversationController(
                        conversationService,
                        assistantService,
                        chatSender,
                        runQueue,
                        runRegistry,
                        runService,
                        events,
                        aiAssistantPort,
                        new ObjectMapper(),
                        mapper,
                        CustomerRateLimiter.unlimited());
        Conversation conversation =
                Conversation.rehydrate(
                        CONVERSATION,
                        CUSTOMER,
                        null,
                        ConversationStatus.ACTIVE,
                        Instant.now(),
                        Instant.now(),
                        Instant.now());
        context = new AssistantService.TurnContext(conversation, List.of());
        turn = new AssistantTurn("你有 3 张订单。", List.of(), new AiUsage(3, 5), "test-model", 42);
        result =
                new AssistantResult(
                        CONVERSATION,
                        turn.text(),
                        List.of(),
                        List.of(),
                        turn.usage(),
                        turn.model(),
                        turn.latencyMs());
    }

    /** 断线的兜底在投递层：第一个 send 撞上断线后，这一轮仍被交给 executeTurn 走完。 */
    @Test
    void clientGoneMidStreamStillHandsTheTurnToExecuteTurn() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        when(aiAssistantPort.respondStreaming(eq(CUSTOMER), any(), anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> onDelta = invocation.getArgument(3);
                            onDelta.accept("你有 "); // 这次 send 撞上断线
                            onDelta.accept("3 张订单。"); // 断线之后不再投递
                            return turn;
                        });
        when(assistantService.executeTurn(eq(context), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Function<List<ChatMessage>, AssistantTurn> modelCall =
                                    invocation.getArgument(2);
                            modelCall.apply(List.of()); // 断线只停投递，不停模型
                            return result;
                        });
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEventBuilder.class));

        controller.streamTurn(emitter, new AtomicBoolean(false), CUSTOMER, CONVERSATION, QUESTION);

        verify(emitter, times(1)).send(any(SseEventBuilder.class)); // 断线后不再尝试投递
        verify(assistantService).executeTurn(eq(context), any(), any()); // 回合照样走完
        verify(assistantService, never()).recordFailure(any(), any(), any());
        verify(emitter).complete();
        // 会话的执行位是这一轮占的，收尾时无论成功失败都要交出去，否则这条会话就再也没人服务。
        verify(runQueue).finished(CONVERSATION);
    }

    /** 容器先一步发现断线（onError/onTimeout 置位）时，一个事件都不该再往线上写，但回合照跑。 */
    @Test
    void containerDetectedDisconnectSkipsDeliveryButStillRunsTheTurn() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        when(aiAssistantPort.respondStreaming(eq(CUSTOMER), any(), anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> onDelta = invocation.getArgument(3);
                            onDelta.accept("你有 3 张订单。");
                            return turn;
                        });
        when(assistantService.executeTurn(eq(context), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Function<List<ChatMessage>, AssistantTurn> modelCall =
                                    invocation.getArgument(2);
                            modelCall.apply(List.of());
                            return result;
                        });

        controller.streamTurn(emitter, new AtomicBoolean(true), CUSTOMER, CONVERSATION, QUESTION);

        verify(emitter, never()).send(any(SseEventBuilder.class));
        verify(assistantService).executeTurn(eq(context), any(), any());
        verify(assistantService, never()).recordFailure(any(), any(), any());
    }

    /** 反向契约：模型失败时 error 事件照旧上线；失败审计与 Run 收尾归 executeTurn，这一层不重复记。 */
    @Test
    void upstreamFailureStillEmitsErrorOnTheWire() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        // A BusinessException's message was written for the caller; it travels verbatim.
        RuntimeException boom = new BusinessException(ErrorCode.UPSTREAM_AI_ERROR, "上游模型超时");
        when(assistantService.executeTurn(eq(context), any(), any())).thenThrow(boom);

        controller.streamTurn(emitter, new AtomicBoolean(false), CUSTOMER, CONVERSATION, QUESTION);

        ArgumentCaptor<SseEventBuilder> events = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter).send(events.capture());
        assertThat(wireOf(events.getValue())).contains("event:error").contains("data:上游模型超时");
        verify(assistantService, never()).recordFailure(any(), any(), any());
        verify(emitter).complete();
    }

    /** 内部细节不上线：原始异常文本（SQL、框架内部、404/403 措辞）留在日志里，线上只给稳定文案。 */
    @Test
    void anInternalFailureIsSanitizedOnTheWireButKeptInTheLog() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        when(assistantService.executeTurn(eq(context), any(), any()))
                .thenThrow(new IllegalStateException("Data too long for column 'response_json'"));

        controller.streamTurn(emitter, new AtomicBoolean(false), CUSTOMER, CONVERSATION, QUESTION);

        ArgumentCaptor<SseEventBuilder> events = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter).send(events.capture());
        String wire = wireOf(events.getValue());
        assertThat(wire).contains("event:error");
        assertThat(wire).doesNotContain("response_json").doesNotContain("Data too long");
        verify(emitter).complete();
    }

    /** 把一个事件还原成线上字节，断言的才是客户端会收到的东西。 */
    private static String wireOf(SseEventBuilder builder) {
        return builder.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining());
    }
}
