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
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
                        .orderToolSpecs(orderService, new ObjectMapper()).stream()
                                .collect(Collectors.toMap(ToolSpec::name, Function.identity()));
    }

    @Test
    void createOrder只把商品与数量交给服务端定价() {
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
    void createOrder缺少数量时被拒绝() {
        assertThatThrownBy(() -> execute("createOrder", "{\"lines\":[{\"sku\":\"GIZMO-03\"}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cancelOrder走状态机把它改成已取消() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED))
                .thenReturn(order(OrderStatus.CANCELLED));

        ToolResult result = execute("cancelOrder", "{\"orderNumber\":\"ORD-1\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED);
        assertThat(result.ref()).isEqualTo("ORD-1");
    }

    @Test
    void updateOrderStatus把合法目标状态交给状态机() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED))
                .thenReturn(order(OrderStatus.COMPLETED));

        execute("updateOrderStatus", "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"COMPLETED\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED);
    }

    @Test
    void updateOrderStatus收到非法目标状态时拒绝为参数错误() {
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
    void queryMyOrders只返回当前客户的订单摘要() {
        when(orderService.listByCustomer(CUSTOMER, 1, 20))
                .thenReturn(PageResult.of(List.of(order(OrderStatus.CREATED)), 1, 1, 20));

        ToolResult result = execute("queryMyOrders", "{}");

        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
        assertThat(result.payloadJson()).contains("\"WIDGET-01\"");
    }

    @Test
    void getOrderByNumber缺少单号时被拒绝() {
        assertThatThrownBy(() -> execute("getOrderByNumber", "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private ToolResult execute(String toolName, String argumentsJson) {
        return specs.get(toolName).handler().execute(CUSTOMER, argumentsJson);
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
}
