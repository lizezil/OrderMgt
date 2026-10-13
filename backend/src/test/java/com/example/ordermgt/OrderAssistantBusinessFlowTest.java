package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.application.ActionCard;
import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.ActionResolution;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.MessageView;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.assistant.infrastructure.persist.AiInvocationMapper;
import com.example.ordermgt.assistant.infrastructure.persist.AiInvocationPO;
import com.example.ordermgt.assistant.infrastructure.schedule.PendingActionSweeper;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderRepository;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * 端到端业务流：把各层拼起来，按"客户会怎么用"走一遍。
 *
 * <p>跑在真实 MySQL 上，模型换成可编程的桩——因为业务闭环的正确性不该取决于模型的脸色 （ADR-0003）。每个用例用独立客户号，互不干扰。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderAssistantBusinessFlowTest {

    private static final AtomicLong CUSTOMER_SEQUENCE = new AtomicLong(7100);

    @TestConfiguration
    static class ScriptedAiConfiguration {

        /** 没有编排脚本时，退回关键词桩，用来跑"真实一点"的查询/下单对话。 */
        static final MockAiAdapter DELEGATE = new MockAiAdapter();

        private static final AtomicReference<Function<List<ChatMessage>, AssistantTurn>> NEXT =
                new AtomicReference<>();

        static void program(Function<List<ChatMessage>, AssistantTurn> behaviour) {
            NEXT.set(behaviour);
        }

        static void reset() {
            NEXT.set(null);
        }

        private static AssistantTurn current(Long customerId, List<ChatMessage> memory) {
            Function<List<ChatMessage>, AssistantTurn> behaviour = NEXT.getAndSet(null);
            return behaviour != null
                    ? behaviour.apply(memory)
                    : DELEGATE.respond(customerId, memory);
        }

        @Bean
        @Primary
        AiAssistantPort scriptedAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
                    return current(customerId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
                    AssistantTurn turn = current(customerId, memory);
                    for (int i = 0; i < turn.text().length(); i += 6) {
                        onTextDelta.accept(
                                turn.text().substring(i, Math.min(turn.text().length(), i + 6)));
                    }
                    return turn;
                }
            };
        }
    }

    /** 每次运行重建 schema：本类断言精确条数（某客户恰好一张订单），残留行会破坏它。 */
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

    @Autowired private ConversationService conversationService;

    @Autowired private OrderService orderService;

    @Autowired private OrderRepository orderRepository;

    @Autowired private PendingActionRepository pendingActionRepository;

    @Autowired private PendingActionSweeper pendingActionSweeper;

    @Autowired private AiInvocationMapper aiInvocationMapper;

    @Autowired private CatalogRepository catalogRepository;

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void clearScript() {
        ScriptedAiConfiguration.reset();
    }

    @Test
    void 目录里的商品与单价是下单的唯一价格来源() throws Exception {
        List<CatalogItem> items = catalogRepository.findAllActive();

        assertThat(items).hasSize(5);
        assertThat(items)
                .extracting(CatalogItem::sku)
                .containsExactlyInAnyOrder(
                        "WIDGET-01", "GADGET-02", "GIZMO-03", "DOODAD-04", "THING-05");
        assertThat(items)
                .filteredOn(item -> item.sku().equals("WIDGET-01"))
                .singleElement()
                .satisfies(item -> assertThat(item.unitPrice()).isEqualByComparingTo("19.90"));

        mockMvc.perform(get("/api/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    @Test
    void 下单金额只认目录价请求里塞的价格被忽略() throws Exception {
        long customer = newCustomer();
        String body =
                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2,\"unitPrice\":0.01,\"lineAmount\":0.02}]}";

        mockMvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", String.valueOf(customer))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalAmount").value(39.80))
                .andExpect(jsonPath("$.data.lines[0].unitPrice").value(19.90));
    }

    @Test
    void 多行订单的总额等于各行目录价合计() throws Exception {
        long customer = newCustomer();

        mockMvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", String.valueOf(customer))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"lines\":[{\"sku\":\"GADGET-02\",\"quantity\":2},{\"sku\":\"THING-05\",\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                // 2 × 49.50 + 1 × 9.99
                .andExpect(jsonPath("$.data.totalAmount").value(108.99));
    }

    @Test
    void 未知商品不能下单() throws Exception {
        long customer = newCustomer();

        mockMvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", String.valueOf(customer))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"NOPE-99\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 客户看不到也打不开别人的订单() throws Exception {
        long alice = newCustomer();
        long bob = newCustomer();
        String orderNo = createOrder(alice, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");

        mockMvc.perform(get("/api/orders/" + orderNo).header("X-Customer-Id", String.valueOf(bob)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/orders").header("X-Customer-Id", String.valueOf(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 助手只回答当前客户自己的订单且不能读别人的会话() throws Exception {
        long alice = newCustomer();
        long bob = newCustomer();
        createOrder(alice, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");

        AssistantResult aliceAnswer = assistantService.chat(alice, null, "我有哪些订单？");
        assertThat(aliceAnswer.executed())
                .singleElement()
                .satisfies(tool -> assertThat(tool.resultJson()).contains("GIZMO-03"));

        AssistantResult bobAnswer = assistantService.chat(bob, null, "我有哪些订单？");
        assertThat(bobAnswer.executed())
                .singleElement()
                .satisfies(tool -> assertThat(tool.resultJson()).isEqualTo("[]"));

        // 记忆属于会话，也属于客户：别人的会话既读不到也写不进。
        Long aliceConversation = aliceAnswer.conversationId();
        assertThatThrownBy(() -> conversationService.history(bob, aliceConversation))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 取消订单必须确认之后才生效() throws Exception {
        long customer = newCustomer();
        String orderNo =
                createOrder(customer, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}");

        ActionCard card = proposeCancel(customer, orderNo);

        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(card.summary()).isEqualTo("取消订单 " + orderNo);
        assertThat(card.title()).isEqualTo("取消订单");
        // 提议是惰性的：确认之前订单一动不动。
        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(OrderStatus.CREATED);

        ActionResolution resolution = actionConfirmationService.confirm(customer, card.token());

        assertThat(resolution.status()).isEqualTo("CONFIRMED");
        assertThat(resolution.replayed()).isFalse();
        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 已取消的订单不能再取消() throws Exception {
        long customer = newCustomer();
        String orderNo =
                createOrder(customer, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");
        ActionCard first = proposeCancel(customer, orderNo);
        actionConfirmationService.confirm(customer, first.token());

        ActionCard second = proposeCancel(customer, orderNo);

        assertThatThrownBy(() -> actionConfirmationService.confirm(customer, second.token()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void 已完成的订单不能再取消() throws Exception {
        long customer = newCustomer();
        String orderNo =
                createOrder(customer, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");

        ScriptedAiConfiguration.program(
                memory ->
                        AssistantTurn.of(
                                "我准备把订单标记为完成。",
                                List.of(
                                        new ProposedToolCall(
                                                "updateOrderStatus",
                                                "{\"orderNumber\":\""
                                                        + orderNo
                                                        + "\",\"targetStatus\":\"COMPLETED\"}",
                                                true)),
                                AiUsage.EMPTY));
        AssistantResult proposal =
                assistantService.chat(customer, null, "把订单 " + orderNo + " 标记完成");
        actionConfirmationService.confirm(customer, proposal.actions().get(0).token());
        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(OrderStatus.COMPLETED);

        ActionCard cancel = proposeCancel(customer, orderNo);

        assertThatThrownBy(() -> actionConfirmationService.confirm(customer, cancel.token()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void staleVersionWriteIsRejectedByOptimisticLock() throws Exception {
        long customer = newCustomer();
        String orderNo =
                createOrder(customer, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");

        // 两个读取者拿到同一版本：第一个取消成功，第二个拿着旧版本的写入必须被拒 --
        // changeStatus 的读是普通快照读，挡不住并发，靠的就是这道版本闸。
        Order stale = orderService.getByNumber(customer, orderNo);
        orderService.changeStatus(customer, orderNo, OrderStatus.CANCELLED);

        stale.transitionTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> orderRepository.save(stale))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 重复拒绝是重放而不是报错() throws Exception {
        long customer = newCustomer();
        String orderNo =
                createOrder(customer, "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}");
        ActionCard card = proposeCancel(customer, orderNo);

        ActionResolution first = actionConfirmationService.reject(customer, card.token());
        ActionResolution second = actionConfirmationService.reject(customer, card.token());

        assertThat(first.status()).isEqualTo("REJECTED");
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 过期的确认单不能被确认也不会产生副作用() {
        long customer = newCustomer();
        Conversation conversation = conversationService.start(customer);
        PendingAction stale =
                PendingAction.propose(
                        customer,
                        conversation.getId(),
                        "createOrder",
                        "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":1}]}",
                        "创建订单：GIZMO-03 × 1",
                        Duration.ofMinutes(-1));
        pendingActionRepository.save(stale);

        pendingActionSweeper.expireOverdue();

        assertThatThrownBy(() -> actionConfirmationService.confirm(customer, stale.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACTION_EXPIRED);
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isZero();
    }

    @Test
    void 确认一张参数非法的卡片会被拒绝且不产生副作用() {
        long customer = newCustomer();
        ScriptedAiConfiguration.program(
                memory ->
                        AssistantTurn.of(
                                "",
                                List.of(
                                        new ProposedToolCall(
                                                "createOrder", "{\"lines\":[]}", true)),
                                AiUsage.EMPTY));

        AssistantResult proposal = assistantService.chat(customer, null, "帮我下个单");
        assertThat(proposal.actions()).hasSize(1);
        assertThat(proposal.actions().get(0).status()).isEqualTo("PENDING");

        // 参数是模型给的、不可信：确认时服务端再校验一次，报 400 而不是照单执行。
        assertThatThrownBy(
                        () ->
                                actionConfirmationService.confirm(
                                        customer, proposal.actions().get(0).token()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isZero();
    }

    @Test
    void 历史回填会把卡片连同它的当前状态还给客户() throws Exception {
        long customer = newCustomer();
        AssistantResult proposal = assistantService.chat(customer, null, "帮我下单 3 个 GIZMO-03");
        actionConfirmationService.confirm(customer, proposal.actions().get(0).token());

        List<MessageView> history =
                conversationService.history(customer, proposal.conversationId());
        List<MessageView> withCards =
                history.stream().filter(view -> view.action() != null).toList();

        assertThat(withCards).hasSize(1);
        assertThat(withCards.get(0).action().toolName()).isEqualTo("createOrder");
        assertThat(withCards.get(0).action().status()).isEqualTo("CONFIRMED");
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void 同一个幂等键在不同客户之间互不影响() throws Exception {
        long alice = newCustomer();
        long bob = newCustomer();
        String body = "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}";

        String aliceOrder = createOrderWithKey(alice, "shared-key-1", body);
        String bobOrder = createOrderWithKey(bob, "shared-key-1", body);

        // 幂等键的作用域是 (客户, 操作, 键)：别人用过的键不能挡住我，也不能把我的请求当成他的重放。
        assertThat(bobOrder).isNotEqualTo(aliceOrder);
        assertThat(orderService.listByCustomer(alice, 1, 20).total()).isEqualTo(1);
        assertThat(orderService.listByCustomer(bob, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void 模型提出未注册的工具时整轮被拒且不落卡片() throws Exception {
        long customer = newCustomer();
        ScriptedAiConfiguration.program(
                memory ->
                        AssistantTurn.of(
                                "",
                                List.of(new ProposedToolCall("deleteEverything", "{}", true)),
                                AiUsage.EMPTY));

        mockMvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", String.valueOf(customer))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"删库跑路\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_AI_ERROR"));

        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isZero();
    }

    @Test
    void 每次模型调用都留一行审计成功的与失败的都有() throws Exception {
        long customer = newCustomer();

        assistantService.chat(customer, null, "我有哪些订单？");

        ScriptedAiConfiguration.program(
                memory -> {
                    throw new AiUpstreamException(
                            ErrorCode.UPSTREAM_AI_ERROR,
                            "模型超时",
                            new RuntimeException("timeout"),
                            "qwen-max",
                            5000,
                            AiInvocationOutcome.ERROR);
                });
        mockMvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", String.valueOf(customer))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"我有哪些订单？\"}"))
                .andExpect(status().isBadGateway());

        List<AiInvocationPO> rows =
                aiInvocationMapper.selectList(
                        Wrappers.<AiInvocationPO>lambdaQuery()
                                .eq(AiInvocationPO::getCustomerId, customer));
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(AiInvocationPO::getOutcome)
                .containsExactlyInAnyOrder("OK", "ERROR");
    }

    @Test
    void 未知路由返回统一的未找到响应() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static long newCustomer() {
        return CUSTOMER_SEQUENCE.incrementAndGet();
    }

    private String createOrder(long customer, String body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/orders")
                                        .header("X-Customer-Id", String.valueOf(customer))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.orderNo");
    }

    private String createOrderWithKey(long customer, String key, String body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/orders")
                                        .header("X-Customer-Id", String.valueOf(customer))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.orderNo");
    }

    private ActionCard proposeCancel(long customer, String orderNo) {
        ScriptedAiConfiguration.program(
                memory ->
                        AssistantTurn.of(
                                "好的，我准备取消订单 " + orderNo + "，请你确认。",
                                List.of(
                                        new ProposedToolCall(
                                                "cancelOrder",
                                                "{\"orderNumber\":\"" + orderNo + "\"}",
                                                true)),
                                AiUsage.EMPTY));
        AssistantResult result = assistantService.chat(customer, null, "取消订单 " + orderNo);
        assertThat(result.actions()).hasSize(1);
        return result.actions().get(0);
    }
}
