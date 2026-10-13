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
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
 * 里已经入库，助手的回复再跟着丢就成了有问无答；审计记 OK， 不把"没人收"算成模型失败。
 *
 * <p>反向契约同样钉住：模型真失败时，error 事件照旧上线、失败审计照旧落盘。
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
    @Mock private AiAssistantPort aiAssistantPort;
    @Mock private AssistantApiMapper mapper;
    @Mock private Executor streamExecutor;
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
                        aiAssistantPort,
                        new ObjectMapper(),
                        mapper,
                        streamExecutor);
        Conversation conversation =
                Conversation.rehydrate(
                        CONVERSATION, CUSTOMER, null, Instant.now(), Instant.now(), Instant.now());
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

    /** 断线的兜底在投递层：第一个 send 撞上断线后，回合必须自己走完。 */
    @Test
    void clientGoneMidStreamStillFinishesAndPersistsTheTurn() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        when(aiAssistantPort.respondStreaming(eq(CUSTOMER), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> onDelta = invocation.getArgument(2);
                            onDelta.accept("你有 "); // 这次 send 撞上断线
                            onDelta.accept("3 张订单。"); // 断线之后不再投递
                            return turn;
                        });
        when(assistantService.complete(context, turn)).thenReturn(result);
        when(mapper.toResponse(result))
                .thenReturn(
                        new ChatResponse(
                                CONVERSATION,
                                turn.text(),
                                List.of(),
                                List.of(),
                                3,
                                5,
                                turn.model(),
                                turn.latencyMs()));
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEventBuilder.class));

        controller.streamTurn(emitter, new AtomicBoolean(false), CUSTOMER, CONVERSATION, QUESTION);

        verify(emitter, times(1)).send(any(SseEventBuilder.class)); // 断线后不再尝试投递
        verify(assistantService).complete(context, turn); // 回合照样走完 → 落库
        verify(assistantService, never()).recordFailure(any(), any(), any());
        verify(emitter).complete();
    }

    /** 容器先一步发现断线（onError/onTimeout 置位）时，一个事件都不该再往线上写。 */
    @Test
    void containerDetectedDisconnectSkipsDeliveryButStillPersists() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        when(aiAssistantPort.respondStreaming(eq(CUSTOMER), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> onDelta = invocation.getArgument(2);
                            onDelta.accept("你有 3 张订单。");
                            return turn;
                        });
        when(assistantService.complete(context, turn)).thenReturn(result);

        controller.streamTurn(emitter, new AtomicBoolean(true), CUSTOMER, CONVERSATION, QUESTION);

        verify(emitter, never()).send(any(SseEventBuilder.class));
        verify(assistantService).complete(context, turn);
        verify(assistantService, never()).recordFailure(any(), any(), any());
    }

    /** 反向契约：模型失败不因"投递层不再抛异常"而漏报——error 事件与失败审计都照旧。 */
    @Test
    void upstreamFailureStillEmitsErrorAndAudits() throws Exception {
        when(assistantService.prepare(CUSTOMER, CONVERSATION, QUESTION)).thenReturn(context);
        RuntimeException boom = new IllegalStateException("上游模型超时");
        when(aiAssistantPort.respondStreaming(eq(CUSTOMER), anyList(), any())).thenThrow(boom);

        controller.streamTurn(emitter, new AtomicBoolean(false), CUSTOMER, CONVERSATION, QUESTION);

        verify(assistantService).recordFailure(context, null, boom);
        verify(assistantService, never()).complete(any(), any());
        ArgumentCaptor<SseEventBuilder> events = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter).send(events.capture());
        assertThat(wireOf(events.getValue())).contains("event:error").contains("data:上游模型超时");
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
