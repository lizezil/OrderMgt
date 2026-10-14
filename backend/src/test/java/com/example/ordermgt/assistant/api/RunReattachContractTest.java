package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.CustomerRateLimiter;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.security.CurrentCustomerResolver;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.example.ordermgt.testsupport.TestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Business rule: 重订阅一个正在跑的 Run（ADR-0014）——首帧给当前累积文本，之后给 token 增量；多个订阅者 都能接；对已经不在跑的 Run
 * 有明确收尾（closed，不悬挂）；越权与不存在的 Run 照旧 403 / 404。
 *
 * <p>增量本身（attach 之后的 delta 扇出）在 {@code RunRegistryTest} 里逐条钉住；这里验的是对外契约的 首帧与收尾。
 */
class RunReattachContractTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long CONVERSATION = 7L;
    private static final Long RUN = 42L;

    private ConversationService conversationService;
    private RunRepository runRepository;
    private RunRegistry runRegistry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        runRepository = mock(RunRepository.class);
        runRegistry = new RunRegistry();

        AssistantService assistantService = mock(AssistantService.class);
        ConversationRunQueue queue = new ConversationRunQueue(Runnable::run, 5);
        AssistantApiMapper mapper = Mappers.getMapper(AssistantApiMapper.class);
        ChatSender chatSender =
                new ChatSender(
                        assistantService,
                        new IdempotencyGuard(
                                mock(IdempotencyRepository.class), new ObjectMapper(), 24),
                        mapper,
                        conversationService,
                        queue,
                        mock(ConversationEventPublisher.class),
                        CustomerRateLimiter.unlimited());

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConversationController(
                                        conversationService,
                                        assistantService,
                                        chatSender,
                                        queue,
                                        runRegistry,
                                        new RunService(runRepository, runRegistry),
                                        mock(ConversationEventPublisher.class),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        mapper,
                                        CustomerRateLimiter.unlimited()))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new CurrentCustomerResolver())
                        // The running app writes SSE frames through Boot's UTF-8 string converter;
                        // standalone MockMvc's default is ISO-8859-1, which would mangle CJK.
                        .setMessageConverters(
                                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                                new MappingJackson2HttpMessageConverter())
                        .build();
    }

    @Test
    void aReattachOpensWithTheTextAccumulatedSoFar() throws Exception {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        RunRegistry.Handle handle = runRegistry.open(RUN);
        handle.append("你有 ");
        handle.append("3 张订单。");

        MvcResult result = reattach().andExpect(status().isOk()).andReturn();

        String frame = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(frame).contains("event:snapshot").contains("\"text\":\"你有 3 张订单。\"");
    }

    @Test
    void twoWatchersEachGetTheirOwnSnapshotOfTheSameRun() throws Exception {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        RunRegistry.Handle handle = runRegistry.open(RUN);
        handle.append("半句");

        String first =
                reattach().andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second =
                reattach().andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(first).contains("\"text\":\"半句\"");
        assertThat(second).contains("\"text\":\"半句\"");
    }

    @Test
    void aRunThatIsNoLongerRunningIsClosedInsteadOfLeftHanging() throws Exception {
        // The row still reads RUNNING (terminal in the database), but this process holds no handle:
        // the subscriber is told so at once rather than waiting on a stream that will never speak.
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

        MvcResult result = reattach().andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("event:closed");
    }

    @Test
    void aRunClosedInTheRegistryIsClosedWhenSubscribed() throws Exception {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));
        RunRegistry.Handle handle = runRegistry.open(RUN);
        handle.append("半句");
        runRegistry.close(RUN);

        MvcResult result = reattach().andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("event:closed");
    }

    @Test
    void aStrangersReattachIsForbiddenBeforeTheRunIsLookedUp() throws Exception {
        when(conversationService.getOwned(CUSTOMER, CONVERSATION))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "forbidden"));

        reattach().andExpect(status().isForbidden());

        verify(runRepository, never()).findById(any());
    }

    @Test
    void aRunFromAnotherConversationIsNotFound() throws Exception {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING, 99L)));

        reattach().andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions reattach() throws Exception {
        return mvc.perform(
                get("/api/assistant/conversations/{id}/runs/{runId}/events", CONVERSATION, RUN)
                        .with(TestAuth.authenticatedAs(CUSTOMER)));
    }

    private static Run run(RunStatus status) {
        return run(status, CONVERSATION);
    }

    private static Run run(RunStatus status, Long conversationId) {
        Instant now = Instant.now();
        return Run.rehydrate(RUN, conversationId, status, null, now, now);
    }
}
