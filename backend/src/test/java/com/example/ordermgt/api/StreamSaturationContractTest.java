package com.example.ordermgt.api;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.api.AssistantApiMapper;
import com.example.ordermgt.assistant.api.ChatSender;
import com.example.ordermgt.assistant.api.ConversationController;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Business rule: 流式池打满时，{@code /stream} 必须在发出任何事件之前回 503，而不是开一条注定排不上队、
 * 只会在超时后失败的流——满载是容量事实，不是流本身的错误。
 */
class StreamSaturationContractTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Executor saturated =
                command -> {
                    throw new RejectedExecutionException("pool saturated");
                };
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConversationController(
                                        mock(ConversationService.class),
                                        mock(AssistantService.class),
                                        mock(ChatSender.class),
                                        // The pool is the thing under test, so the run queue is
                                        // real:
                                        // a turn that cannot get a thread must surface as 503, not
                                        // leave the conversation's slot held.
                                        new ConversationRunQueue(saturated, 5),
                                        new RunRegistry(),
                                        mock(RunService.class),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        Mappers.getMapper(AssistantApiMapper.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void saturatedPoolAnswersServiceUnavailableBeforeStreaming() throws Exception {
        mvc.perform(
                        get("/api/assistant/conversations/{id}/stream", 7L)
                                .header("X-Customer-Id", 1001L)
                                .queryParam("message", "我有哪些订单？"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }
}
