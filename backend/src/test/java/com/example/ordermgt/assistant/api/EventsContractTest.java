package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.ConversationEventBus;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.ConversationView;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationState;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.security.CurrentCustomerResolver;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.example.ordermgt.testsupport.TestAuth;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Business rule: 状态流的对外契约（ADR-0014）——首帧就是该客户全部会话的全量 snapshot（含归档），之后才有 增量（state / queue /
 * ping）；每个客户能开的连接数有上限，超了如实回 503；重连再拿一次全量，客户端整体替换， 不做 Last-Event-ID。
 */
class EventsContractTest {

    private static final Long CUSTOMER = 1001L;

    private ConversationService conversationService;
    private ConversationEventBus bus;
    private SseEmitter emitter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        // Heartbeat off: this test never needs a real connection kept alive.
        bus = new ConversationEventBus(0, 2);
        emitter = mock(SseEmitter.class);
        EventsController controller =
                new EventsController(
                        conversationService,
                        bus,
                        Mappers.getMapper(AssistantApiMapper.class),
                        // Like the running app's mapper, ISO-8601 dates included: a bare
                        // ObjectMapper
                        // cannot write Instant, and the snapshot carries one per conversation.
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build(),
                        () -> emitter);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new CurrentCustomerResolver())
                        .build();
    }

    @Test
    void theFirstFrameIsAFullSnapshotOfEveryConversationIncludingArchived() throws Exception {
        when(conversationService.listFor(CUSTOMER, true))
                .thenReturn(
                        List.of(
                                view(7L, "在用的", ConversationStatus.ACTIVE, ConversationState.IDLE),
                                view(
                                        9L,
                                        "压箱底的",
                                        ConversationStatus.ARCHIVED,
                                        ConversationState.IDLE)));

        mvc.perform(get("/api/assistant/events").with(TestAuth.authenticatedAs(CUSTOMER)))
                .andExpect(status().isOk());

        String frame = wireOf(singleSend());
        assertThat(frame).contains("event:snapshot");
        assertThat(frame).contains("\"id\":7").contains("\"id\":9");
        assertThat(frame).contains("\"status\":\"ARCHIVED\"");
    }

    @Test
    void anIncrementAfterTheSnapshotIsPushedOnTheSameConnection() throws Exception {
        when(conversationService.listFor(CUSTOMER, true)).thenReturn(List.of());

        mvc.perform(get("/api/assistant/events").with(TestAuth.authenticatedAs(CUSTOMER)))
                .andExpect(status().isOk());
        // The stream is registered, so a later state change goes to the very same emitter.
        bus.publish(CUSTOMER, "state", "{\"id\":7,\"state\":\"RUNNING\"}");

        ArgumentCaptor<SseEventBuilder> sent = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter, times(2)).send(sent.capture());
        assertThat(wireOf(sent.getAllValues().get(1)))
                .contains("event:state")
                .contains("\"state\":\"RUNNING\"");
    }

    @Test
    void aCustomerAtTheConnectionCapIsRefusedRatherThanSilentlyDropped() throws Exception {
        // The cap is 2; hold both, then the next attempt must say so -- before any frame.
        bus.register(CUSTOMER, new SseEmitter(0L));
        bus.register(CUSTOMER, new SseEmitter(0L));

        mvc.perform(get("/api/assistant/events").with(TestAuth.authenticatedAs(CUSTOMER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }

    private SseEventBuilder singleSend() throws Exception {
        ArgumentCaptor<SseEventBuilder> sent = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter).send(sent.capture());
        return sent.getValue();
    }

    /** Renders an event back to the bytes a client would actually receive. */
    private static String wireOf(SseEventBuilder builder) {
        return builder.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining());
    }

    private static ConversationView view(
            Long id, String title, ConversationStatus status, ConversationState state) {
        Instant now = Instant.now();
        return new ConversationView(
                Conversation.rehydrate(id, CUSTOMER, title, status, now, now, now),
                state,
                state == ConversationState.RUNNING ? 42L : null);
    }
}
