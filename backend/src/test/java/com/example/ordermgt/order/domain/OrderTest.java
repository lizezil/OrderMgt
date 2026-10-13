package com.example.ordermgt.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 订单聚合的不变量。
 *
 * <p>一张订单必须属于某位客户、至少含一行、总额是各行金额之和；订单一旦创建，只有状态可以移动， 且只能沿着 CREATED → COMPLETED / CANCELLED
 * 走。这些规则是"对账不会错、已终态不可再改"的底线。
 */
class OrderTest {

    private static final BigDecimal WIDGET_PRICE = new BigDecimal("19.90");
    private static final BigDecimal GIZMO_PRICE = new BigDecimal("5.00");

    @Test
    void 订单必须属于一位客户() {
        assertThatThrownBy(() -> Order.create(null, List.of(widget(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 订单至少需要一行商品() {
        assertThatThrownBy(() -> Order.create(1001L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(1001L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 新订单初始为已创建且带有可读单号() {
        Order order = Order.create(1001L, List.of(widget(1)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getOrderNumber().value()).startsWith("ORD");
        assertThat(order.getCustomerId()).isEqualTo(1001L);
    }

    @Test
    void 订单总额等于各行金额之和() {
        Order order =
                Order.create(
                        1001L,
                        List.of(
                                OrderLine.of("WIDGET-01", "Widget", 3, WIDGET_PRICE),
                                OrderLine.of("GIZMO-03", "Gizmo", 2, GIZMO_PRICE)));

        // 3 × 19.90 + 2 × 5.00 = 69.70
        assertThat(order.getTotalAmount().amount()).isEqualByComparingTo("69.70");
        assertThat(order.getLines()).hasSize(2);
    }

    @Test
    void 行金额等于单价乘以数量() {
        OrderLine line = OrderLine.of("WIDGET-01", "Widget", 3, WIDGET_PRICE);

        assertThat(line.lineAmount().amount()).isEqualByComparingTo("59.70");
    }

    @Test
    void 商品行的数量必须为正且必须有商品编号() {
        assertThatThrownBy(() -> OrderLine.of("WIDGET-01", "Widget", 0, WIDGET_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderLine.of("WIDGET-01", "Widget", -1, WIDGET_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderLine.of("  ", "Widget", 1, WIDGET_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 已创建的订单可以完成或取消() {
        Order order = Order.create(1001L, List.of(widget(1)));

        assertThat(order.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
        assertThat(order.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void 完成或取消之后订单不再可变更() {
        Order completed = Order.create(1001L, List.of(widget(1)));
        completed.transitionTo(OrderStatus.COMPLETED);

        assertThat(completed.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThatThrownBy(() -> completed.transitionTo(OrderStatus.CANCELLED))
                .isInstanceOf(IllegalStateException.class);

        Order cancelled = Order.create(1002L, List.of(widget(1)));
        cancelled.transitionTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> cancelled.transitionTo(OrderStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 订单不能停留在原状态() {
        Order order = Order.create(1001L, List.of(widget(1)));

        assertThat(order.canTransitionTo(OrderStatus.CREATED)).isFalse();
    }

    @Test
    void 合法流转会改变状态并记录更新时间() {
        Order order = Order.create(1001L, List.of(widget(1)));
        Instant before = order.getUpdatedAt();

        order.transitionTo(OrderStatus.CANCELLED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    private static OrderLine widget(int quantity) {
        return OrderLine.of("WIDGET-01", "Widget", quantity, WIDGET_PRICE);
    }
}
