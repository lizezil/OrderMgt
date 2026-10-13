package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * scripts/smoke-demo.sh 的可回归版本：同一条演示路径，全程只走 HTTP 接口（建会话 → 只读查询 → 下单出卡片 → 确认 → 重复确认 →
 * 越权），区别只在于模型是桩、客户号唯一、库每次重建，所以能进 CI。
 *
 * <p>脚本第 6 步"记忆"断言的是模型回复里的数字，那依赖真实模型；这里换成断言第二轮喂给模型的上下文 里确实带着第一轮 --
 * 服务端能保证的是记忆被回放，召回的准确度属于模型能力（ADR-0003）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SmokeDemoFlowTest {

    private static final AtomicLong CUSTOMER_SEQUENCE = new AtomicLong(7300);

    @TestConfiguration
    static class RecordingAiConfiguration {

        /** 关键词桩与离线演示同一个；这里额外记下每一轮喂给模型的记忆。 */
        static final MockAiAdapter DELEGATE = new MockAiAdapter();

        static final AtomicReference<List<ChatMessage>> LAST_MEMORY = new AtomicReference<>();

        @Bean
        @Primary
        AiAssistantPort recordingAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
                    LAST_MEMORY.set(List.copyOf(memory));
                    return DELEGATE.respond(customerId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
                    LAST_MEMORY.set(List.copyOf(memory));
                    return DELEGATE.respondStreaming(customerId, memory, onTextDelta);
                }
            };
        }
    }

    /** 每次运行重建 schema，理由同 AssistantFlowTest：本类也断言精确订单数。 */
    @TestConfiguration
    static class CleanSchemaConfiguration {

        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void forgetLastMemory() {
        RecordingAiConfiguration.LAST_MEMORY.set(null);
    }

    @Test
    void 演示路径走一遍从只读查询到越权被拒() throws Exception {
        long customer = newCustomer();
        long other = newCustomer();
        long conversationId = startConversation(customer);

        // 1) 只读：查询当场执行，回复里带着工具查回的结果和用量。
        JsonNode readOnly = send(conversationId, customer, "{\"message\":\"我有哪些订单？\"}");
        assertThat(readOnly.get("reply").asText()).contains("queryMyOrders");
        assertThat(readOnly.get("model").asText()).isEqualTo("offline-mock");
        assertThat(readOnly.get("promptTokens").asInt()).isNotNegative();
        assertThat(readOnly.get("completionTokens").asInt()).isNotNegative();

        // 2) 变更：只出卡片，不执行。
        JsonNode proposal = send(conversationId, customer, "{\"message\":\"帮我下单 2 个 DOODAD-04\"}");
        assertThat(proposal.get("actions")).hasSize(1);
        assertThat(proposal.get("actions").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(proposal.get("actions").get(0).get("summary").asText())
                .isEqualTo("创建订单：DOODAD-04 × 2");
        String token = proposal.get("actions").get(0).get("token").asText();
        assertThat(orderTotal(customer)).isZero();

        // 3) 确认：这时才落库，金额按目录价算。
        mockMvc.perform(
                        post("/api/assistant/actions/" + token + "/confirm")
                                .header("X-Customer-Id", String.valueOf(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.resultJson", containsString("\"totalAmount\":240.00")));
        assertThat(orderTotal(customer)).isEqualTo(1);

        // 4) 重复确认：重放上一次的结果，不产生第二张订单。
        mockMvc.perform(
                        post("/api/assistant/actions/" + token + "/confirm")
                                .header("X-Customer-Id", String.valueOf(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(orderTotal(customer)).isEqualTo(1);

        // 5) 越权：别人的令牌用不了，别人的订单也一条都看不到。
        mockMvc.perform(
                        post("/api/assistant/actions/" + token + "/confirm")
                                .header("X-Customer-Id", String.valueOf(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(orderTotal(other)).isZero();
    }

    @Test
    void 第二轮提问带着第一轮的上下文() throws Exception {
        long customer = newCustomer();
        long conversationId = startConversation(customer);
        String first = "帮我下单 2 个 DOODAD-04";
        String second = "我刚才要下单几个 DOODAD-04？只回答数字。";

        send(conversationId, customer, "{\"message\":\"" + first + "\"}");
        RecordingAiConfiguration.LAST_MEMORY.set(null);
        send(conversationId, customer, "{\"message\":\"" + second + "\"}");

        // 记忆属于会话：第二轮的提示词里带着第一轮，模型才有"刚才"可指。
        assertThat(RecordingAiConfiguration.LAST_MEMORY.get())
                .extracting(ChatMessage::content)
                .contains(first, second);

        // 历史接口把客户说过的两句原样还回来（每轮助手的回复与卡片也夹在里面），
        // 会话换了设备也还在。
        JsonNode history = history(conversationId, customer);
        List<String> customerSaid = new ArrayList<>();
        for (JsonNode line : history) {
            if ("CUSTOMER".equals(line.get("role").asText())) {
                customerSaid.add(line.get("content").asText());
            }
        }
        assertThat(customerSaid).containsExactly(first, second);
    }

    private static long newCustomer() {
        return CUSTOMER_SEQUENCE.incrementAndGet();
    }

    private long startConversation(long customer) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/assistant/conversations")
                                        .header("X-Customer-Id", String.valueOf(customer)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.id").isNumber())
                        .andReturn();
        return readTree(result).get("data").get("id").asLong();
    }

    private JsonNode send(long conversationId, long customer, String body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/assistant/conversations/" + conversationId + "/messages")
                                        .header("X-Customer-Id", String.valueOf(customer))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        return readTree(result).get("data");
    }

    private JsonNode history(long conversationId, long customer) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/assistant/conversations/" + conversationId + "/messages")
                                        .header("X-Customer-Id", String.valueOf(customer)))
                        .andExpect(status().isOk())
                        .andReturn();
        return readTree(result).get("data");
    }

    private long orderTotal(long customer) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/orders")
                                        .header("X-Customer-Id", String.valueOf(customer)))
                        .andExpect(status().isOk())
                        .andReturn();
        return readTree(result).get("data").get("total").asLong();
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
