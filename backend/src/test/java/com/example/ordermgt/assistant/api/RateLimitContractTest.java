package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.CustomerRateLimiter;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.security.CurrentCustomerResolver;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.example.ordermgt.testsupport.TestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 业务规则：每个客户每分钟最多 N 次 AI 入口（chat 与 stream 一起计数），第 N+1 次是 429 RATE_LIMITED， 且必须带
 * Retry-After——等到窗口里最早那次滑出的秒数。窗口滑动：等到点之后同一客户照常放行。
 *
 * <p>限流必须发生在流被承诺之前：stream 越限的回答是普通 JSON 429，而不是一条已经打开的 SSE 流里 的 error 帧——后者客户端可能已经当成功连接处理了。
 */
class RateLimitContractTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long CONVERSATION = 7L;

    private AssistantService assistantService;
    private ConversationService conversationService;
    private AtomicLong clock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        assistantService = mock(AssistantService.class);
        conversationService = mock(ConversationService.class);
        clock = new AtomicLong(0);

        when(assistantService.chat(anyLong(), any(), anyString()))
                .thenReturn(
                        new AssistantResult(
                                CONVERSATION,
                                "好的",
                                List.of(),
                                List.of(),
                                new AiUsage(1, 2),
                                "test-model",
                                10));
        when(conversationService.getOwned(anyLong(), anyLong())).thenReturn(null);

        // 额度刻意压到 2，窗口 60 秒由限流器内部固定；时钟注入让"滑出"可以精确推进。
        CustomerRateLimiter limiter = new CustomerRateLimiter(2, clock::get);

        AssistantApiMapper mapper = Mappers.getMapper(AssistantApiMapper.class);
        ChatSender chatSender =
                new ChatSender(
                        assistantService,
                        new IdempotencyGuard(
                                mock(IdempotencyRepository.class), new ObjectMapper(), 24),
                        mapper,
                        conversationService,
                        new ConversationRunQueue(Runnable::run, 5),
                        mock(ConversationEventPublisher.class),
                        limiter);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AssistantController(chatSender),
                                new ConversationController(
                                        conversationService,
                                        assistantService,
                                        chatSender,
                                        new ConversationRunQueue(Runnable::run, 5),
                                        new RunRegistry(),
                                        mock(RunService.class),
                                        mock(ConversationEventPublisher.class),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        mapper,
                                        limiter))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new CurrentCustomerResolver())
                        .build();
    }

    @Test
    void chatPastTheLimitIs429WithRetryAfterAndRunsNoModelCall() throws Exception {
        chat().andExpect(status().isOk());
        chat().andExpect(status().isOk());

        chat().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "60"));

        // 恰好两次：放行的那两次到了模型，被拒的那次没有——限的是入口，不是事后补刀。
        verify(assistantService, times(2)).chat(anyLong(), isNull(), anyString());
    }

    @Test
    void streamPastTheLimitIsJson429BeforeAnySseFrame() throws Exception {
        stream().andExpect(status().isOk());
        stream().andExpect(status().isOk());

        MvcResult refused =
                stream()
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                        .andExpect(header().string("Retry-After", "60"))
                        .andReturn();

        // 普通 JSON，不是一条已经承诺的 SSE 流：Content-Type 必须说明白。
        assertThat(refused.getResponse().getContentType())
                .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void onceTheWindowSlidesTheSameCustomerIsAdmittedAgain() throws Exception {
        chat().andExpect(status().isOk());
        chat().andExpect(status().isOk());
        chat().andExpect(status().isTooManyRequests());

        clock.addAndGet(60_001);

        chat().andExpect(status().isOk());
    }

    @Test
    void theBudgetIsSharedAcrossBothEntryPoints() throws Exception {
        chat().andExpect(status().isOk());
        chat().andExpect(status().isOk());

        // 第三次走 stream：同一个客户、同一个窗口——两个入口花的是同一份预算，不是各算各的。
        stream()
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void anotherCustomersQuotaIsNotSpentByTheFirst() throws Exception {
        chat().andExpect(status().isOk());
        chat().andExpect(status().isOk());
        chat().andExpect(status().isTooManyRequests());

        mvc.perform(
                        post("/api/assistant/chat")
                                .with(TestAuth.authenticatedAs(2002L))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions chat() throws Exception {
        return mvc.perform(
                post("/api/assistant/chat")
                        .with(TestAuth.authenticatedAs(CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions stream() throws Exception {
        return mvc.perform(
                get("/api/assistant/conversations/{id}/stream", CONVERSATION)
                        .with(TestAuth.authenticatedAs(CUSTOMER))
                        .queryParam("message", "你好"));
    }
}
