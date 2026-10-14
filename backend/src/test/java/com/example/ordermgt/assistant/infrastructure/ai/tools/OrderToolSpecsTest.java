package com.example.ordermgt.assistant.infrastructure.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.api.Paging;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Business rule: 每个工具真正去做的那件事。
 *
 * <p>模型只提供"工具名 + 结构化参数"；参数怎么变成业务动作，由这里的 handler 决定。所以要验证 createOrder 只把 sku
 * 与数量交给下单服务（价格不由模型决定）、取消/改状态确实走状态机、以及模型 给出的非法参数会被拒绝成 400 而不是把服务打崩。
 */
class OrderToolSpecsTest {

    private static final Long CUSTOMER = 1001L;

    private OrderService orderService;

    private Map<String, ToolSpec> specs;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        specs =
                new OrderToolDefinitions()
                                .orderToolSpecs(
                                        orderService,
                                        new ObjectMapper(),
                                        mock(CatalogRepository.class))
                                .stream()
                                .collect(Collectors.toMap(ToolSpec::name, Function.identity()));
    }

    @Test
    void createOrderPassesOnlySkuAndQuantityForServerSidePricing() {
        when(orderService.createOrder(eq(CUSTOMER), anyList()))
                .thenReturn(order(OrderStatus.CREATED));

        ToolResult result =
                execute("createOrder", "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LineCommand>> lines = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(CUSTOMER), lines.capture());
        // 参数里只有 sku 与数量，没有任何金额字段——价格只能来自 catalog。
        assertThat(lines.getValue()).containsExactly(new LineCommand("GIZMO-03", 3));
        assertThat(result.ref()).isEqualTo("ORD-1");
        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
    }

    @Test
    void createOrderRejectsMissingQuantity() {
        assertThatThrownBy(() -> execute("createOrder", "{\"lines\":[{\"sku\":\"GIZMO-03\"}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cancelOrderUsesStateMachineToCancel() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED))
                .thenReturn(order(OrderStatus.CANCELLED));

        ToolResult result = execute("cancelOrder", "{\"orderNumber\":\"ORD-1\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED);
        assertThat(result.ref()).isEqualTo("ORD-1");
    }

    @Test
    void updateOrderStatusPassesValidTargetStatusToStateMachine() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED))
                .thenReturn(order(OrderStatus.COMPLETED));

        execute("updateOrderStatus", "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"COMPLETED\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED);
    }

    @Test
    void updateOrderStatusRejectsInvalidTargetStatusAsParameterError() {
        // 模型可以无视 schema 的 enum：服务端必须把不认识的枚举值挡成 400，而不是让它变成一次 500。
        assertThatThrownBy(
                        () ->
                                execute(
                                        "updateOrderStatus",
                                        "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"BOGUS\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersReturnsOnlyCurrentCustomerOrderSummaries() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(order(OrderStatus.CREATED)), 1, 1, 20));

        ToolResult result = execute("queryMyOrders", "{}");

        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
        assertThat(result.payloadJson()).contains("\"WIDGET-01\"");
    }

    @Test
    void getOrderByNumberReturnsTheSummaryOfAnOrderedOrder() {
        when(orderService.getByNumber(CUSTOMER, "ORD-1")).thenReturn(order(OrderStatus.CREATED));

        ToolResult result = execute("getOrderByNumber", "{\"orderNumber\":\"ORD-1\"}");

        // 查的是发起人的单：customerId 来自登录态，模型只能给订单号，替不了别人查。
        verify(orderService).getByNumber(CUSTOMER, "ORD-1");
        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
        assertThat(result.payloadJson()).contains("\"WIDGET-01\"");
    }

    @Test
    void getOrderByNumberRejectsMissingOrderNumber() {
        assertThatThrownBy(() -> execute("getOrderByNumber", "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private ToolResult execute(String toolName, String argumentsJson) {
        return specs.get(toolName).handler().execute(CUSTOMER, argumentsJson);
    }

    private String report(String toolName, ToolResult result) {
        return specs.get(toolName).report().describe(result.payloadJson());
    }

    private static Order order(OrderStatus status) {
        Instant now = Instant.now();
        return Order.rehydrate(
                null,
                new OrderNumber("ORD-1"),
                CUSTOMER,
                status,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                now,
                now,
                0);
    }

    private static Order order(String orderNumber, OrderStatus status) {
        Instant now = Instant.now();
        return Order.rehydrate(
                null,
                new OrderNumber(orderNumber),
                CUSTOMER,
                status,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                now,
                now,
                0);
    }

    @Test
    void anEmptyOrderListSaysSoInsteadOfShowingAnEmptyArray() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));

        assertThat(report("queryMyOrders", execute("queryMyOrders", "{}"))).isEqualTo("你名下还没有订单。");
    }

    @Test
    void theOrderListIsNarratedWithChineseStatusAndDollarAmounts() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(order(OrderStatus.CREATED)), 1, 1, 20));

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        // 报告是负载的纯投影：字段名换成人话，机器标识（订单号、sku）留在行内代码里。
        assertThat(rendered)
                .isEqualTo(
                        """
                        你名下有 1 张订单：

                        `ORD-1` · 已创建 · ¥19.90
                        - `WIDGET-01` Widget × 1 = ¥19.90""");
    }

    @Test
    void everyStatusHasACustomerFacingLabel() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(
                        PageResult.of(
                                List.of(
                                        order("ORD-1", OrderStatus.CREATED),
                                        order("ORD-2", OrderStatus.COMPLETED),
                                        order("ORD-3", OrderStatus.CANCELLED)),
                                3,
                                1,
                                20));

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        assertThat(rendered).contains("你名下有 3 张订单：");
        assertThat(rendered).contains("`ORD-1` · 已创建 · ¥19.90");
        assertThat(rendered).contains("`ORD-2` · 已完成 · ¥19.90");
        assertThat(rendered).contains("`ORD-3` · 已取消 · ¥19.90");
    }

    @Test
    void aFullPageSaysItIsOnlyAPageNotTheWholeHistory() {
        int page = (int) Paging.DEFAULT_SIZE;
        when(orderService.listByCustomer(CUSTOMER, 1, page))
                .thenReturn(
                        PageResult.of(
                                IntStream.range(0, page)
                                        .mapToObj(
                                                index -> order("ORD-" + index, OrderStatus.CREATED))
                                        .toList(),
                                page,
                                1,
                                page));

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        // 20 张正好是上限：顾客不能把这一页当成全部历史。
        assertThat(rendered).endsWith("（只列出最近 " + page + " 张）");
    }

    @Test
    void aSingleOrderIsNarratedWithTheSameBlockAsTheList() {
        when(orderService.getByNumber(CUSTOMER, "ORD-1")).thenReturn(order(OrderStatus.CREATED));

        assertThat(
                        report(
                                "getOrderByNumber",
                                execute("getOrderByNumber", "{\"orderNumber\":\"ORD-1\"}")))
                .isEqualTo(
                        """
                        `ORD-1` · 已创建 · ¥19.90
                        - `WIDGET-01` Widget × 1 = ¥19.90""");
    }
}
