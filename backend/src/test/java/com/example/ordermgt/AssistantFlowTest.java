package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.ordermgt.assistant.domain.RunCancellation;
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
import com.example.ordermgt.support.CleanSchemaConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
@Import(CleanSchemaConfiguration.class)
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
                        Long customerId,
                        List<ChatMessage> memory,
                        Consumer<String> onTextDelta,
                        RunCancellation cancellation) {
                    INVOCATIONS.incrementAndGet();
                    return DELEGATE.respondStreaming(customerId, memory, onTextDelta, cancellation);
                }
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
        assistantService.chat(customer, null, "help me order 3 GIZMO-03");

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
    void theAuditEndpointIsReadableByItsOwnerAndNobodyElse() throws Exception {
        Long owner = OWNER + 7;
        Long conversationId =
                assistantService.chat(owner, null, "help me order 3 GIZMO-03").conversationId();

        mockMvc.perform(
                        get("/api/assistant/conversations/{id}/invocations", conversationId)
                                .with(user(String.valueOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].model").value("offline-mock"))
                .andExpect(jsonPath("$.data[0].outcome").value("OK"))
                .andExpect(jsonPath("$.data[0].latencyMs").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());

        // Someone else's conversation is indistinguishable from one that does not exist -- 403,
        // never an outline of their model traffic.
        mockMvc.perform(
                        get("/api/assistant/conversations/{id}/invocations", conversationId)
                                .with(user(String.valueOf(owner + 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anExpiredKeyIsDeletedByTheSweepButAFreshOneIsLeftAlone() {
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

        idempotencySweeper.deleteExpired();

        // Past its expiry the row is gone: the key becomes claimable again, replays stop, and the
        // table stays bounded -- retention is a fact, not a comment.
        assertThat(idempotencyRepository.find(customer, "SEND_MESSAGE", "stale-key")).isEmpty();
        assertThat(
                        idempotencyRepository
                                .find(customer, "SEND_MESSAGE", "fresh-key")
                                .orElseThrow()
                                .status())
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void anOversizedResponseStillStoresSoAReplayNeverTurnsIntoARerun() {
        Long customer = OWNER + 7;
        Instant now = Instant.now();
        var claim =
                idempotencyRepository
                        .tryClaim(
                                new IdempotencyRecord(
                                        null,
                                        customer,
                                        "CREATE_ORDER",
                                        "fat-response-key",
                                        null,
                                        IdempotencyStatus.IN_PROGRESS,
                                        null,
                                        null,
                                        now,
                                        null,
                                        now.plusSeconds(3600)))
                        .orElseThrow();
        // A large order serializes well past the old VARCHAR(8000): storing it must succeed,
        // because a store failure used to mark the key FAILED and invite a duplicate order.
        String oversized = "{\"reply\":\"" + "x".repeat(9000) + "\"}";

        idempotencyRepository.complete(claim.id(), oversized);

        assertThat(
                        idempotencyRepository
                                .find(customer, "CREATE_ORDER", "fat-response-key")
                                .orElseThrow()
                                .responseJson())
                .isEqualTo(oversized);
    }

    @Test
    void theStreamingPathProducesTheSameTurnAsTheBlockingOne() {
        List<String> deltas = new ArrayList<>();

        AssistantService.TurnContext context =
                assistantService.prepare(9700L, null, "help me order 3 GIZMO-03");
        AssistantTurn turn =
                aiAssistantPort.respondStreaming(
                        9700L, context.memory(), deltas::add, RunCancellation.NEVER);
        AssistantResult result = assistantService.complete(context, turn);

        assertThat(String.join("", deltas)).isEqualTo(turn.text());
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).status()).isEqualTo("PENDING");
        // Streaming changes how the text arrives, never whether a mutation is applied.
        assertThat(orderService.listByCustomer(9700L, 1, 20).total()).isZero();
    }

    @Test
    void nothingIsCreatedUntilTheCustomerConfirms() {
        AssistantResult proposal = assistantService.chat(OWNER, null, "help me order 3 GIZMO-03");

        assertThat(proposal.actions()).hasSize(1);
        assertThat(proposal.actions().get(0).status()).isEqualTo("PENDING");
        assertThat(proposal.actions().get(0).summary()).isEqualTo("Place order: GIZMO-03 × 3");
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
        AssistantResult proposal =
                assistantService.chat(OWNER + 1, null, "help me order 3 GIZMO-03");
        String token = proposal.actions().get(0).token();

        actionConfirmationService.confirm(OWNER + 1, token);
        ActionResolution second = actionConfirmationService.confirm(OWNER + 1, token);

        assertThat(second.replayed()).isTrue();
        assertThat(orderService.listByCustomer(OWNER + 1, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void aStrangerCannotConfirm() {
        AssistantResult proposal =
                assistantService.chat(OWNER + 2, null, "help me order 3 GIZMO-03");
        String token = proposal.actions().get(0).token();

        assertThatThrownBy(() -> actionConfirmationService.confirm(OWNER + 3, token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(orderService.listByCustomer(OWNER + 2, 1, 20).total()).isZero();
    }

    @Test
    void rejectingLeavesTheDataAlone() {
        AssistantResult proposal =
                assistantService.chat(OWNER + 4, null, "help me order 3 GIZMO-03");
        ActionResolution rejection =
                actionConfirmationService.reject(OWNER + 4, proposal.actions().get(0).token());

        assertThat(rejection.status()).isEqualTo("REJECTED");
        assertThat(orderService.listByCustomer(OWNER + 4, 1, 20).total()).isZero();
    }

    @Test
    void aRetriedChatDoesNotCallTheModelTwice() throws Exception {
        int before = StubAiConfiguration.INVOCATIONS.get();
        String body = "{\"message\":\"help me order 3 GIZMO-03\"}";

        MvcResult first =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .with(user("9500"))
                                        .header("Idempotency-Key", "chat-key-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.actions[0].token").isNotEmpty())
                        .andReturn();

        MvcResult retry =
                mockMvc.perform(
                                post("/api/assistant/chat")
                                        .with(user("9500"))
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
                                .with(user("9600"))
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
                                        .with(user("9600"))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.orderNo");
    }
}
