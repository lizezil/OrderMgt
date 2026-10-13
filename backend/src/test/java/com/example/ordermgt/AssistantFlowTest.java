package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.ActionResolution;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.assistant.infrastructure.persist.AiInvocationMapper;
import com.example.ordermgt.assistant.infrastructure.persist.AiInvocationPO;
import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.idempotency.infrastructure.schedule.IdempotencySweeper;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 * The whole chain against a real database and a stubbed model: the AI proposes, nothing happens
 * until a customer confirms, a repeat confirmation does not create a second order, and a stranger
 * cannot confirm anything.
 *
 * <p>The model is stubbed on purpose -- tests must not spend API quota or depend on a remote
 * service's mood. That is exactly why AiAssistantPort exists (ADR-0003).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssistantFlowTest {

    private static final Long OWNER = 9001L;

    @TestConfiguration
    static class StubAiConfiguration {

        static final AtomicInteger INVOCATIONS = new AtomicInteger();

        /**
         * The offline adapter is the stub: the tests exercise the same keyword-driven fake a
         * key-less demo uses, wrapped only to count model calls (the retry test needs that).
         */
        static final AiAssistantPort DELEGATE = new MockAiAdapter();

        @Bean
        @Primary
        AiAssistantPort stubAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
                    INVOCATIONS.incrementAndGet();
                    return DELEGATE.respond(customerId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
                    INVOCATIONS.incrementAndGet();
                    return DELEGATE.respondStreaming(customerId, memory, onTextDelta);
                }
            };
        }
    }

    /**
     * Rebuilds the schema for every run. The suite runs against a resident MySQL, so rows would
     * otherwise accumulate: the assertions below count exact rows (a customer has exactly one
     * order) and reuse fixed idempotency keys, which a leftover row from an earlier run would
     * break. clean() drops everything, migrate() replays V1 -- so each run starts from the seed.
     */
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

    @Autowired private AssistantService assistantService;

    @Autowired private ActionConfirmationService actionConfirmationService;

    @Autowired private OrderService orderService;

    @Autowired private MockMvc mockMvc;

    @Autowired private AiAssistantPort aiAssistantPort;

    @Autowired private AiInvocationMapper aiInvocationMapper;

    @Autowired private IdempotencyRepository idempotencyRepository;

    @Autowired private IdempotencySweeper idempotencySweeper;

    @Test
    void everyModelCallIsWrittenToTheAuditTable() {
        Long customer = OWNER + 5;
        assistantService.chat(customer, null, "帮我下单 3 个 GIZMO-03");

        List<AiInvocationPO> rows =
                aiInvocationMapper.selectList(
                        Wrappers.<AiInvocationPO>lambdaQuery()
                                .eq(AiInvocationPO::getCustomerId, customer));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOutcome()).isEqualTo("OK");
        assertThat(rows.get(0).getConversationId()).isNotNull();
        // The proposal the model asked for lands on the row too: audit shows intent, not just
        // tokens.
        assertThat(rows.get(0).getToolCallsJson()).contains("createOrder");
    }

    @Test
    void aStaleInProgressKeyIsSweptToFailedButAFreshOneIsLeftAlone() {
        Long customer = OWNER + 6;
        Instant now = Instant.now();
        idempotencyRepository.tryClaim(
                new IdempotencyRecord(
                        null,
                        customer,
                        "SEND_MESSAGE",
                        "stale-key",
                        null,
                        IdempotencyStatus.IN_PROGRESS,
                        null,
                        null,
                        now.minusSeconds(3600),
                        null,
                        now.minusSeconds(60)));
        idempotencyRepository.tryClaim(
                new IdempotencyRecord(
                        null,
                        customer,
                        "SEND_MESSAGE",
                        "fresh-key",
                        null,
                        IdempotencyStatus.IN_PROGRESS,
                        null,
                        null,
                        now,
                        null,
                        now.plusSeconds(3600)));

        idempotencySweeper.expireStaleInProgress();

        // Past its expiry and still IN_PROGRESS means the process died mid-flight: fail it so the
        // key can be retried.
        assertThat(
                        idempotencyRepository
                                .find(customer, "SEND_MESSAGE", "stale-key")
                                .orElseThrow()
                                .status())
                .isEqualTo(IdempotencyStatus.FAILED);
        assertThat(
                        idempotencyRepository
                                .find(customer, "SEND_MESSAGE", "fresh-key")
                                .orElseThrow()
                                .status())
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void theStreamingPathProducesTheSameTurnAsTheBlockingOne() {
        List<String> deltas = new ArrayList<>();

        AssistantService.TurnContext context =
                assistantService.prepare(9700L, null, "帮我下单 3 个 GIZMO-03");
        AssistantTurn turn = aiAssistantPort.respondStreaming(9700L, context.memory(), deltas::add);
        AssistantResult result = assistantService.complete(context, turn);

        assertThat(String.join("", deltas)).isEqualTo(turn.text());
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).status()).isEqualTo("PENDING");
        // Streaming changes how the text arrives, never whether a mutation is applied.
        assertThat(orderService.listByCustomer(9700L, 1, 20).total()).isZero();
    }

    @Test
    void nothingIsCreatedUntilTheCustomerConfirms() {
        AssistantResult proposal = assistantService.chat(OWNER, null, "帮我下单 3 个 GIZMO-03");

        assertThat(proposal.actions()).hasSize(1);
        assertThat(proposal.actions().get(0).status()).isEqualTo("PENDING");
        assertThat(proposal.actions().get(0).summary()).isEqualTo("创建订单：GIZMO-03 × 3");
        // The proposal is inert: the handler was never reached.
        assertThat(orderService.listByCustomer(OWNER, 1, 20).total()).isZero();

        String token = proposal.actions().get(0).token();
        ActionResolution resolution = actionConfirmationService.confirm(OWNER, token);

        assertThat(resolution.status()).isEqualTo("CONFIRMED");
        assertThat(resolution.replayed()).isFalse();
        assertThat(resolution.resultJson()).contains("\"totalAmount\":15.00");

        var orders = orderService.listByCustomer(OWNER, 1, 20);
        assertThat(orders.total()).isEqualTo(1);
        assertThat(orders.items().get(0).getLines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.sku()).isEqualTo("GIZMO-03");
                            assertThat(line.quantity()).isEqualTo(3);
                        });
    }

    @Test
    void confirmingTwiceAppliesTheChangeOnce() {
        AssistantResult proposal = assistantService.chat(OWNER + 1, null, "帮我下单 3 个 GIZMO-03");
        String token = proposal.actions().get(0).token();

        actionConfirmationService.confirm(OWNER + 1, token);
        ActionResolution second = actionConfirmationService.confirm(OWNER + 1, token);

        assertThat(second.replayed()).isTrue();
        assertThat(orderService.listByCustomer(OWNER + 1, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void aStrangerCannotConfirm() {
        AssistantResult proposal = assistantService.chat(OWNER + 2, null, "帮我下单 3 个 GIZMO-03");
        String token = proposal.actions().get(0).token();

        assertThatThrownBy(() -> actionConfirmationService.confirm(OWNER + 3, token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(orderService.listByCustomer(OWNER + 2, 1, 20).total()).isZero();
    }

    @Test
    void rejectingLeavesTheDataAlone() {
        AssistantResult proposal = assistantService.chat(OWNER + 4, null, "帮我下单 3 个 GIZMO-03");
        ActionResolution rejection =
                actionConfirmationService.reject(OWNER + 4, proposal.actions().get(0).token());

        assertThat(rejection.status()).isEqualTo("REJECTED");
        assertThat(orderService.listByCustomer(OWNER + 4, 1, 20).total()).isZero();
    }

    @Test
    void aRetriedChatDoesNotCallTheModelTwice() throws Exception {
        int before = StubAiConfiguration.INVOCATIONS.get();
        String body = "{\"message\":\"帮我下单 3 个 GIZMO-03\"}";

        MvcResult first =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .header("X-Customer-Id", "9500")
                                        .header("Idempotency-Key", "chat-key-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.actions[0].token").isNotEmpty())
                        .andReturn();

        MvcResult retry =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .header("X-Customer-Id", "9500")
                                        .header("Idempotency-Key", "chat-key-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(retry.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // One model call for the request and one for the replay-proof retry would be two.
        assertThat(StubAiConfiguration.INVOCATIONS.get() - before).isEqualTo(1);
    }

    @Test
    void aRetriedOrderCreationProducesOneOrder() throws Exception {
        String body = "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}";

        String firstOrderNo = createOrderWithKey("ORD-IDEM-1", body);
        String retryOrderNo = createOrderWithKey("ORD-IDEM-1", body);

        assertThat(retryOrderNo).isEqualTo(firstOrderNo);
        assertThat(orderService.listByCustomer(9600L, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentBodyIsRejected() throws Exception {
        createOrderWithKey("ORD-IDEM-2", "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}");

        mockMvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", "9600")
                                .header("Idempotency-Key", "ORD-IDEM-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":9}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private String createOrderWithKey(String key, String body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/orders")
                                        .header("X-Customer-Id", "9600")
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.orderNo");
    }
}
