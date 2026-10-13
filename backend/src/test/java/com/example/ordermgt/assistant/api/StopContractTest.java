package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Business rule: 停止的对外契约（ADR-0012）——只对 RUNNING 生效且幂等；已终态再停无副作用；不存在/不属于这条 会话的 Run 报
 * 404；身份校验先于一切，外人停不了别人的 Run，也问不出它存不存在。
 */
class StopContractTest {

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
                        queue);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConversationController(
                                        conversationService,
                                        assistantService,
                                        chatSender,
                                        queue,
                                        runRegistry,
                                        new RunService(runRepository, runRegistry),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        mapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void stoppingARunningRunAnswersOkAndTripsItsCancellation() throws Exception {
        RunRegistry.Handle handle = runRegistry.open(RUN);
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

        stop().andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));

        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    void stoppingAFinishedRunIsIdempotent() throws Exception {
        RunRegistry.Handle handle = runRegistry.open(RUN);
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        stop().andExpect(status().isOk());

        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    void stoppingAnUnknownRunIsNotFound() throws Exception {
        when(runRepository.findById(RUN)).thenReturn(Optional.empty());

        stop().andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aStrangersStopIsForbiddenBeforeTheRunIsEvenLookedUp() throws Exception {
        when(conversationService.getOwned(CUSTOMER, CONVERSATION))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "forbidden"));

        stop().andExpect(status().isForbidden());

        // Ownership is checked first: a stranger cannot even learn whether the run exists.
        verify(runRepository, never()).findById(any());
    }

    private org.springframework.test.web.servlet.ResultActions stop() throws Exception {
        return mvc.perform(
                post("/api/assistant/conversations/{id}/runs/{runId}/stop", CONVERSATION, RUN)
                        .header("X-Customer-Id", CUSTOMER));
    }

    private static Run run(RunStatus status) {
        Instant now = Instant.now();
        return Run.rehydrate(RUN, CONVERSATION, status, null, now, now);
    }
}
