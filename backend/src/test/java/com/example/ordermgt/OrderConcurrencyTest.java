package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.ActionResolution;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.support.CleanSchemaConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
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
 * 并发场景：多个线程同时打同一个业务对象（同一张确认卡、同一个幂等键、同一张订单），验证系统的 并发正确性保证在真实线程 + 真实 MySQL
 * 下成立——悲观行锁的重放、唯一约束的仲裁、乐观锁的版本闸， 这些在单线程测试里都只是"代码长得像对的"。
 *
 * <p>每个用例用独立客户号（7500 段），互不干扰；模型仍用关键词桩（ADR-0003），并发只发生在 业务路径上。并发基座是 CountDownLatch
 * 齐射：所有线程在栅栏前就位后同时放行，最大化重叠窗口。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CleanSchemaConfiguration.class)
class OrderConcurrencyTest {

    private static final AtomicLong CUSTOMER_SEQUENCE = new AtomicLong(7500);

    /** 计数桩：失败重试的竞态用例靠它断言"动作只执行了一次"。 */
    @TestConfiguration
    static class CountingStubAiConfiguration {

        static final AtomicInteger INVOCATIONS = new AtomicInteger();

        static final AiAssistantPort DELEGATE = new MockAiAdapter();

        @Bean
        @Primary
        AiAssistantPort countingStubAiAssistantPort() {
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

    @Autowired private IdempotencyRepository idempotencyRepository;

    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void resetInvocationCount() {
        CountingStubAiConfiguration.INVOCATIONS.set(0);
    }

    @Test
    void concurrentConfirmationsOfSameCardApplyOnlyOnce() {
        long customer = newCustomer();
        String token = proposeCreateOrder(customer);

        List<Outcome<ActionResolution>> outcomes =
                runConcurrently(
                        Collections.nCopies(
                                6, () -> actionConfirmationService.confirm(customer, token)));

        // 全部成功：先拿到行锁的执行工具，其余拿锁后读到 CONFIRMED，走重放而不是二次执行。
        assertThat(outcomes).allMatch(Outcome::ok);
        List<ActionResolution> resolutions = extractValues(outcomes);
        assertThat(resolutions).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(resolutions).filteredOn(ActionResolution::replayed).hasSize(5);
        assertThat(resolutions).allMatch(r -> r.status().equals("CONFIRMED"));
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void concurrentConfirmAndRejectOnlyFirstArrivalWins() {
        long customer = newCustomer();
        String token = proposeCreateOrder(customer);

        List<Outcome<ActionResolution>> outcomes =
                runConcurrently(
                        List.of(
                                () -> actionConfirmationService.confirm(customer, token),
                                () -> actionConfirmationService.reject(customer, token)));

        List<Outcome<ActionResolution>> winners = outcomes.stream().filter(Outcome::ok).toList();
        assertThat(winners).hasSize(1);
        Outcome<ActionResolution> loser =
                outcomes.stream().filter(o -> !o.ok()).findFirst().orElseThrow();
        assertConflict(loser.error());

        // 终态必须与赢家一致：确认赢则落单，拒绝赢则什么都不该发生。
        boolean confirmed = winners.get(0).value().status().equals("CONFIRMED");
        long expectedOrders = confirmed ? 1 : 0;
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isEqualTo(expectedOrders);
    }

    @Test
    void concurrentOrdersWithSameIdempotencyKeyCreateOnlyOneOrder() throws Exception {
        long customer = newCustomer();
        String key = "race-order-" + customer;
        String body = "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":2}]}";

        List<Outcome<HttpReply>> outcomes =
                runConcurrently(
                        Collections.nCopies(
                                4,
                                () -> {
                                    MvcResult result =
                                            mockMvc.perform(
                                                            post("/api/orders")
                                                                    .with(
                                                                            user(
                                                                                    String.valueOf(
                                                                                            customer)))
                                                                    .header("Idempotency-Key", key)
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .content(body))
                                                    .andReturn();
                                    return new HttpReply(
                                            result.getResponse().getStatus(),
                                            result.getResponse().getContentAsString());
                                }));

        assertThat(outcomes).allMatch(Outcome::ok);
        List<HttpReply> replies = extractValues(outcomes);
        // 赢家落单；输家要么撞上 IN_PROGRESS 得 409，要么读到 COMPLETED 拿到重放——绝不能有第二单。
        assertThat(replies).allMatch(r -> r.status() == 201 || r.status() == 409);
        assertThat(replies).filteredOn(r -> r.status() == 201).isNotEmpty();
        List<String> orderNos =
                replies.stream()
                        .filter(r -> r.status() == 201)
                        .map(r -> (String) JsonPath.read(r.body(), "$.data.orderNo"))
                        .distinct()
                        .toList();
        assertThat(orderNos).hasSize(1);
        for (HttpReply reply : replies) {
            if (reply.status() == 409) {
                assertThat((String) JsonPath.read(reply.body(), "$.code"))
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
            }
        }
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void concurrentOrdersWithoutIdempotencyKeyDoNotInterfere() {
        long customer = newCustomer();

        List<Outcome<Order>> outcomes =
                runConcurrently(
                        List.of(
                                () -> createOrder(customer, 1),
                                () -> createOrder(customer, 2),
                                () -> createOrder(customer, 3),
                                () -> createOrder(customer, 4),
                                () -> createOrder(customer, 5),
                                () -> createOrder(customer, 6)));

        assertThat(outcomes).allMatch(Outcome::ok);
        List<Order> orders = extractValues(outcomes);
        assertThat(orders)
                .allSatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED));
        // 订单号是秒级时间戳 + 6 位 hex：并发下也不能撞（撞了 uk_orders_no 会 500）。
        assertThat(orders)
                .extracting(order -> order.getOrderNumber().value())
                .doesNotHaveDuplicates()
                .hasSize(6);
        for (Order order : orders) {
            int quantity = order.getLines().get(0).quantity();
            assertThat(order.getTotalAmount().amount())
                    .isEqualByComparingTo(
                            BigDecimal.valueOf(5).multiply(BigDecimal.valueOf(quantity)));
        }
        assertThat(orderService.listByCustomer(customer, 1, 20).total()).isEqualTo(6);
    }

    @Test
    void concurrentStatusTransitionsAllowOnlyOneWinner() {
        long customer = newCustomer();
        String orderNo = createOrder(customer, 1).getOrderNumber().value();

        List<Outcome<Order>> outcomes =
                runConcurrently(
                        List.of(
                                () ->
                                        orderService.changeStatus(
                                                customer, orderNo, OrderStatus.COMPLETED),
                                () ->
                                        orderService.changeStatus(
                                                customer, orderNo, OrderStatus.COMPLETED),
                                () ->
                                        orderService.changeStatus(
                                                customer, orderNo, OrderStatus.CANCELLED),
                                () ->
                                        orderService.changeStatus(
                                                customer, orderNo, OrderStatus.CANCELLED)));

        // 检查与写入必须被版本闸绑在一起：读到同一版本的并发流转，只有一个能提交，其余 409。
        List<Order> winners = outcomes.stream().filter(Outcome::ok).map(Outcome::value).toList();
        assertThat(winners).hasSize(1);
        outcomes.stream().filter(o -> !o.ok()).forEach(o -> assertConflict(o.error()));
        assertThat(orderService.getByNumber(customer, orderNo).getStatus())
                .isEqualTo(winners.get(0).getStatus());
    }

    @Test
    void concurrentRetryAfterIdempotencyFailureExecutesActionOnce() throws Exception {
        long customer = newCustomer();
        String key = "retry-race-" + customer;
        String message = "help me order 3 GIZMO-03";

        // 前置：一次失败的尝试把键留在 FAILED（Guard 允许 FAILED 被重试）。
        Instant now = Instant.now();
        idempotencyRepository.tryClaim(
                new IdempotencyRecord(
                        null,
                        customer,
                        "SEND_MESSAGE",
                        key,
                        IdempotencyGuard.hashOf("null", message),
                        IdempotencyStatus.FAILED,
                        null,
                        null,
                        now,
                        now,
                        now.plus(Duration.ofHours(24))));

        List<Outcome<HttpReply>> outcomes =
                runConcurrently(
                        Collections.nCopies(
                                6,
                                () -> {
                                    MvcResult result =
                                            mockMvc.perform(
                                                            post("/api/assistant/chat")
                                                                    .with(
                                                                            user(
                                                                                    String.valueOf(
                                                                                            customer)))
                                                                    .header("Idempotency-Key", key)
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .content(
                                                                            "{\"message\":\""
                                                                                    + message
                                                                                    + "\"}"))
                                                    .andReturn();
                                    return new HttpReply(
                                            result.getResponse().getStatus(),
                                            result.getResponse().getContentAsString());
                                }));

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(extractValues(outcomes)).allMatch(r -> r.status() == 200 || r.status() == 409);

        // 并发正确性语义：同一幂等键的动作至多执行一次——读到 FAILED 的线程都来重试时，
        // reclaim() 的条件更新（WHERE status='FAILED'）只放行一个，其余重读记录后要么撞
        // IN_PROGRESS 得 409，要么读到 COMPLETED 拿重放。
        assertThat(CountingStubAiConfiguration.INVOCATIONS.get()).isEqualTo(1);
    }

    private static long newCustomer() {
        return CUSTOMER_SEQUENCE.incrementAndGet();
    }

    /** 关键词桩把"N SKU"翻成一张待确认的 createOrder 卡片；返回卡片令牌。 */
    private String proposeCreateOrder(long customer) {
        AssistantResult proposal =
                assistantService.chat(customer, null, "help me order 3 GIZMO-03");
        assertThat(proposal.actions()).hasSize(1);
        return proposal.actions().get(0).token();
    }

    private Order createOrder(long customer, int quantity) {
        return orderService.createOrder(customer, List.of(new LineCommand("GIZMO-03", quantity)));
    }

    private static void assertConflict(Throwable error) {
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    private static <T> List<T> extractValues(List<Outcome<T>> outcomes) {
        return outcomes.stream().map(Outcome::value).toList();
    }

    /** 齐射：所有任务在栅栏前就位后同时放行，返回每个线程的值或抛出的异常。 */
    private <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> pending = new ArrayList<>();
        for (Callable<T> task : tasks) {
            pending.add(
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    return new Outcome<>(task.call(), null);
                                } catch (Throwable t) {
                                    return new Outcome<>(null, t);
                                }
                            }));
        }
        start.countDown();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "concurrent tasks did not finish within 30 seconds");
            }
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : pending) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("concurrent tasks did not terminate normally", e);
        }
    }

    /** 一个线程的结局：要么拿到值，要么带着抛出的异常——断言时两者都有用。 */
    private record Outcome<T>(T value, Throwable error) {

        boolean ok() {
            return error == null;
        }
    }

    private record HttpReply(int status, String body) {}
}
