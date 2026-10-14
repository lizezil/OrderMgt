package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.support.CleanSchemaConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
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
 * 归档的真实落库行为（ADR-0013）：归档置 status=ARCHIVED 并从默认列表隐藏、带 includeArchived 仍可读； 有未过期卡片的会话归档被 409
 * 拒绝；向已归档的会话发一条消息，它自动恢复为 ACTIVE。
 *
 * <p>与其它 L4 一样跑真 MySQL + 离线桩模型，所以整条「发消息 → 归档 → 自动恢复」的路径是真跑的。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({CleanSchemaConfiguration.class, ArchiveIntegrationTest.StubAiConfiguration.class})
class ArchiveIntegrationTest {

    private static final Long CUSTOMER = 9300L;

    @TestConfiguration
    static class StubAiConfiguration {

        /** The offline keyword adapter: deterministic, billed nowhere. */
        @Bean
        @Primary
        AiAssistantPort stubAiAssistantPort() {
            MockAiAdapter delegate = new MockAiAdapter();
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
                    return delegate.respond(customerId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId,
                        List<ChatMessage> memory,
                        Consumer<String> onTextDelta,
                        RunCancellation cancellation) {
                    return delegate.respondStreaming(customerId, memory, onTextDelta, cancellation);
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private ConversationService conversationService;

    @Test
    void archivingHidesItFromTheDefaultListButKeepsItReadable() throws Exception {
        Long conversationId = startConversation(CUSTOMER);

        archive(conversationId, CUSTOMER).andExpect(status().isOk());

        assertThat(conversationService.getOwned(CUSTOMER, conversationId).getStatus())
                .isEqualTo(ConversationStatus.ARCHIVED);
        // Hidden by default, visible on request -- and still readable either way.
        list(false, CUSTOMER).andExpect(jsonPath("$.data.length()").value(0));
        list(true, CUSTOMER)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ARCHIVED"));
    }

    @Test
    void archivingAConversationWithALiveCardIsRejected() throws Exception {
        Long customer = CUSTOMER + 1;
        Long conversationId = startConversation(customer);
        // An order proposal leaves a live confirmation card -> WAITING_APPROVAL, i.e. not
        // quiescent.
        sendBlocking(conversationId, customer, "help me order 3 GIZMO-03");

        archive(conversationId, customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void speakingToAnArchivedConversationRestoresIt() throws Exception {
        Long customer = CUSTOMER + 2;
        Long conversationId = startConversation(customer);
        archive(conversationId, customer).andExpect(status().isOk());

        // Saying something to it is an explicit intent to resume it (ADR-0013).
        sendBlocking(conversationId, customer, "what orders do I have?");

        assertThat(conversationService.getOwned(customer, conversationId).getStatus())
                .isEqualTo(ConversationStatus.ACTIVE);
        list(false, customer).andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    private Long startConversation(Long customer) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/assistant/conversations")
                                        .with(user(String.valueOf(customer))))
                        .andExpect(status().isCreated())
                        .andReturn();
        return id(result);
    }

    private org.springframework.test.web.servlet.ResultActions archive(
            Long conversationId, Long customer) throws Exception {
        return mockMvc.perform(
                post("/api/assistant/conversations/{id}/archive", conversationId)
                        .with(user(String.valueOf(customer))));
    }

    private void sendBlocking(Long conversationId, Long customer, String message) throws Exception {
        mockMvc.perform(
                        post("/api/assistant/conversations/{id}/messages", conversationId)
                                .with(user(String.valueOf(customer)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions list(
            boolean includeArchived, Long customer) throws Exception {
        return mockMvc.perform(
                        get("/api/assistant/conversations")
                                .with(user(String.valueOf(customer)))
                                .queryParam("includeArchived", String.valueOf(includeArchived)))
                .andExpect(status().isOk());
    }

    private Long id(MvcResult result) throws Exception {
        JsonNode data =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("id").asLong();
    }
}
