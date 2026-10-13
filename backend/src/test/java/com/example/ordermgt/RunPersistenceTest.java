package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.assistant.infrastructure.persist.RunMapper;
import com.example.ordermgt.assistant.infrastructure.persist.RunPO;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.support.CleanSchemaConfiguration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Run is the execution unit, so it must be visible in the database, not just in memory (ADR-0011):
 * both send paths leave a RUNNING -> COMPLETED row, a failed model call leaves a FAILED row with
 * the reason, and the sidebar reads its state off those rows.
 *
 * <p>Against the real MySQL and a stubbed model, like {@code AssistantFlowTest}: the model is
 * offline (keyword-driven) so the suite spends no quota.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({CleanSchemaConfiguration.class, RunPersistenceTest.StubAiConfiguration.class})
class RunPersistenceTest {

    private static final Long CUSTOMER = 9100L;

    @TestConfiguration
    static class StubAiConfiguration {

        /** Every call for this customer fails, so the FAILED path can be exercised offline. */
        static final Long FAILING_CUSTOMER = 9199L;

        static final AiAssistantPort DELEGATE = new MockAiAdapter();

        @Bean
        @Primary
        AiAssistantPort stubAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
                    failIfDoomed(customerId);
                    return DELEGATE.respond(customerId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
                    failIfDoomed(customerId);
                    return DELEGATE.respondStreaming(customerId, memory, onTextDelta);
                }

                private void failIfDoomed(Long customerId) {
                    if (FAILING_CUSTOMER.equals(customerId)) {
                        throw new AiUpstreamException(
                                ErrorCode.UPSTREAM_AI_ERROR,
                                "AI upstream call failed",
                                new RuntimeException("provider down"),
                                "offline-mock",
                                12,
                                AiInvocationOutcome.ERROR);
                    }
                }
            };
        }
    }

    @Autowired private AssistantService assistantService;

    @Autowired private AiAssistantPort aiAssistantPort;

    @Autowired private RunMapper runMapper;

    @Autowired private MockMvc mockMvc;

    @Test
    void aSynchronousTurnLeavesOneCompletedRun() {
        AssistantResult result = assistantService.chat(CUSTOMER, null, "help me order 3 GIZMO-03");

        List<RunPO> runs = runsOf(result.conversationId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(runs.get(0).getStartedAt()).isNotNull();
        assertThat(runs.get(0).getEndedAt()).isNotNull();
        assertThat(runs.get(0).getError()).isNull();
    }

    /** The streaming transport differs only in how the model is called: same Run, same closure. */
    @Test
    void aStreamingTurnLeavesOneCompletedRun() {
        Long customer = CUSTOMER + 1;
        AssistantService.TurnContext context =
                assistantService.prepare(customer, null, "help me order 3 GIZMO-03");
        List<String> deltas = new ArrayList<>();

        assistantService.executeTurn(
                context,
                () -> aiAssistantPort.respondStreaming(customer, context.memory(), deltas::add));

        List<RunPO> runs = runsOf(context.conversation().getId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(runs.get(0).getEndedAt()).isNotNull();
    }

    @Test
    void aFailedModelCallLeavesAFailedRunWithTheReason() {
        Long customer = StubAiConfiguration.FAILING_CUSTOMER;
        AssistantService.TurnContext context = assistantService.prepare(customer, null, "hello");

        assertThatThrownBy(
                        () ->
                                assistantService.executeTurn(
                                        context,
                                        () -> aiAssistantPort.respond(customer, context.memory())))
                .isInstanceOf(AiUpstreamException.class);

        List<RunPO> runs = runsOf(context.conversation().getId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(runs.get(0).getError()).isNotNull();
        assertThat(runs.get(0).getEndedAt()).isNotNull();
    }

    @Test
    void theConversationListCarriesStatusAndDerivedState() throws Exception {
        Long customer = CUSTOMER + 10;
        Long conversationId =
                assistantService.chat(customer, null, "help me order 3 GIZMO-03").conversationId();

        // The turn finished (Run COMPLETED) but its createOrder card is still live ->
        // WAITING_APPROVAL.
        listFor(customer)
                .andExpect(jsonPath("$.data[0].id").value(conversationId))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].state").value("WAITING_APPROVAL"));

        // A RUNNING run wins over the card.
        runMapper.insert(runningRun(conversationId));
        listFor(customer).andExpect(jsonPath("$.data[0].state").value("RUNNING"));
    }

    private org.springframework.test.web.servlet.ResultActions listFor(Long customer)
            throws Exception {
        return mockMvc.perform(
                        get("/api/assistant/conversations")
                                .header("X-Customer-Id", String.valueOf(customer)))
                .andExpect(status().isOk());
    }

    private List<RunPO> runsOf(Long conversationId) {
        return runMapper.selectList(
                Wrappers.<RunPO>lambdaQuery().eq(RunPO::getConversationId, conversationId));
    }

    private static RunPO runningRun(Long conversationId) {
        RunPO po = new RunPO();
        po.setConversationId(conversationId);
        po.setStatus("RUNNING");
        po.setStartedAt(Instant.now());
        return po;
    }
}
