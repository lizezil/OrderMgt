package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.shared.logging.MdcContext;
import com.example.ordermgt.shared.logging.TraceIdFilter;
import com.example.ordermgt.support.CleanSchemaConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * US-8.5 的端到端契约：一次请求的全部日志行能用四个关联标识串起来——请求的 traceId、顾客的 customerId、 会话的 conversationId、这一回合的 runId。
 *
 * <p>这里走真实的一条链（filter → 安全链 → controller → 队列 → 流式池 → 模型桩），在模型调用那一刻把 MDC 抓下来：JSON
 * 入口在请求线程上抓，流式入口在被池工人扛走之后抓——后者才是这条故事的要害，线程换了，标识不换。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CleanSchemaConfiguration.class)
class TraceIdContractTest {

    private static final Long CUSTOMER = 9331L;

    @TestConfiguration
    static class StubAiConfiguration {

        /** What the MDC looked like at the moment the model call was made, on whichever thread. */
        static final List<Capture> CAPTURES = new CopyOnWriteArrayList<>();

        static final AiAssistantPort DELEGATE = new MockAiAdapter();

        record Capture(
                String threadName,
                String traceId,
                String customerId,
                String conversationId,
                String runId) {}

        @Bean
        @Primary
        AiAssistantPort stubAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(
                        Long customerId, Long conversationId, List<ChatMessage> memory) {
                    CAPTURES.add(capture());
                    return DELEGATE.respond(customerId, conversationId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId,
                        Long conversationId,
                        List<ChatMessage> memory,
                        Consumer<String> onTextDelta,
                        RunCancellation cancellation) {
                    CAPTURES.add(capture());
                    return DELEGATE.respondStreaming(
                            customerId, conversationId, memory, onTextDelta, cancellation);
                }
            };
        }

        private static Capture capture() {
            return new Capture(
                    Thread.currentThread().getName(),
                    MDC.get(MdcContext.TRACE_ID),
                    MDC.get(MdcContext.CUSTOMER_ID),
                    MDC.get(MdcContext.CONVERSATION_ID),
                    MDC.get(MdcContext.RUN_ID));
        }
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void theJsonTransportRunsTheModelCallWithTheWholeTrailOnIt() throws Exception {
        int before = StubAiConfiguration.CAPTURES.size();

        mockMvc.perform(
                        post("/api/assistant/chat")
                                .with(user(String.valueOf(CUSTOMER)))
                                .header(TraceIdFilter.HEADER, "trace-json-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, "trace-json-1"));

        StubAiConfiguration.Capture capture = awaitCapture(before);
        assertThat(capture.traceId()).isEqualTo("trace-json-1");
        assertThat(capture.customerId()).isEqualTo(String.valueOf(CUSTOMER));
        assertThat(capture.conversationId()).isNotBlank();
        assertThat(capture.runId()).isNotBlank();
    }

    @Test
    void withoutAnInboundHeaderTheRequestMintsOneAndEchoesIt() throws Exception {
        int before = StubAiConfiguration.CAPTURES.size();

        MvcResult result =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .with(user(String.valueOf(CUSTOMER + 1)))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"message\":\"你好\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        String echoed = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(echoed).isNotBlank();
        // 模型那次调用看到的正是响应回显给调用方的那个 id：排障时两边对得上。
        assertThat(awaitCapture(before).traceId()).isEqualTo(echoed);
    }

    @Test
    void theStreamingTransportKeepsTheIdsOntoThePoolWorker() throws Exception {
        Long conversationId = newConversation(CUSTOMER);
        int before = StubAiConfiguration.CAPTURES.size();

        MvcResult streamed =
                mockMvc.perform(
                                get("/api/assistant/conversations/{id}/stream", conversationId)
                                        .with(user(String.valueOf(CUSTOMER)))
                                        .header(TraceIdFilter.HEADER, "trace-stream-1")
                                        .queryParam("message", "你好"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(streamed.getResponse().getHeader(TraceIdFilter.HEADER))
                .isEqualTo("trace-stream-1");

        StubAiConfiguration.Capture capture = awaitCapture(before);
        // 线程换了（模型调用在流式池的工人线程上），四个标识一个不落。
        assertThat(capture.threadName()).startsWith("assistant-stream");
        assertThat(capture.traceId()).isEqualTo("trace-stream-1");
        assertThat(capture.customerId()).isEqualTo(String.valueOf(CUSTOMER));
        assertThat(capture.conversationId()).isEqualTo(String.valueOf(conversationId));
        assertThat(capture.runId()).isNotBlank();
    }

    /**
     * 已知边界的守门：filter 排在安全链之后，被 401 挡下的请求到不了它——所以安全拒绝不带 traceId。 这是取 customerId 需要登录态的代价（记在附录
     * C），这里钉住的是"边界没变"，不是"边界应当如此"。
     */
    @Test
    void aRequestSecurityRefusedCarriesNoTraceId() throws Exception {
        mockMvc.perform(
                        post("/api/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(TraceIdFilter.HEADER));
    }

    private Long newConversation(Long customer) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .with(user(String.valueOf(customer)))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"message\":\"你好\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Number conversationId =
                JsonPath.read(created.getResponse().getContentAsString(), "$.data.conversationId");
        return conversationId.longValue();
    }

    /**
     * The model call happens on a pool worker for the streaming leg: wait until it has happened.
     */
    private static StubAiConfiguration.Capture awaitCapture(int before)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (StubAiConfiguration.CAPTURES.size() <= before
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(StubAiConfiguration.CAPTURES.size()).isGreaterThan(before);
        return StubAiConfiguration.CAPTURES.get(before);
    }
}
