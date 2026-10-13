package com.example.ordermgt.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderRepository;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.error.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Business rule: 下单定价、订单归属、分页与状态变更。
 *
 * <p>从客户视角看，这一段决定了"我下单要付多少钱、我能看到谁的订单、我能不能改这张单"。价格只认 目录，客户身份决定可见范围，终态不可再改。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;

    @Mock private CatalogRepository catalogRepository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, catalogRepository);
    }

    @Test
    void 下单金额完全由目录价决定() {
        when(catalogRepository.findBySku("WIDGET-01"))
                .thenReturn(Optional.of(item("WIDGET-01", "Widget", "19.90", true)));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order order = service.createOrder(1001L, List.of(new LineCommand("WIDGET-01", 2)));

        assertThat(order.getTotalAmount().amount()).isEqualByComparingTo("39.80");
        assertThat(order.getLines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.sku()).isEqualTo("WIDGET-01");
                            assertThat(line.unitPrice().amount()).isEqualByComparingTo("19.90");
                            assertThat(line.lineAmount().amount()).isEqualByComparingTo("39.80");
                        });
    }

    @Test
    void 多行订单的总额是各行目录价合计() {
        when(catalogRepository.findBySku("GADGET-02"))
                .thenReturn(Optional.of(item("GADGET-02", "Gadget", "49.50", true)));
        when(catalogRepository.findBySku("THING-05"))
                .thenReturn(Optional.of(item("THING-05", "Thingamajig", "9.99", true)));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order order =
                service.createOrder(
                        1001L,
                        List.of(new LineCommand("GADGET-02", 2), new LineCommand("THING-05", 1)));

        // 2 × 49.50 + 1 × 9.99 = 108.99
        assertThat(order.getTotalAmount().amount()).isEqualByComparingTo("108.99");
    }

    @Test
    void 未知商品不能下单() {
        when(catalogRepository.findBySku("NOPE-99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(1001L, List.of(new LineCommand("NOPE-99", 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 已下架商品不能下单() {
        when(catalogRepository.findBySku("WIDGET-01"))
                .thenReturn(Optional.of(item("WIDGET-01", "Widget", "19.90", false)));

        assertThatThrownBy(
                        () -> service.createOrder(1001L, List.of(new LineCommand("WIDGET-01", 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 订单至少要有一行商品() {
        assertThatThrownBy(() -> service.createOrder(1001L, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.createOrder(1001L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 数量非正的行不能落单() {
        when(catalogRepository.findBySku("WIDGET-01"))
                .thenReturn(Optional.of(item("WIDGET-01", "Widget", "19.90", true)));

        assertThatThrownBy(
                        () -> service.createOrder(1001L, List.of(new LineCommand("WIDGET-01", 0))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 列表只查当前客户的订单() {
        when(orderRepository.findByCustomer(eq(7L), anyLong(), anyLong())).thenReturn(List.of());
        when(orderRepository.countByCustomer(7L)).thenReturn(0L);

        service.listByCustomer(7L, 3, 50);

        verify(orderRepository).findByCustomer(7L, 100L, 50L);
    }

    @Test
    void 非法的分页参数被收窄回安全值() {
        when(orderRepository.findByCustomer(eq(7L), anyLong(), anyLong())).thenReturn(List.of());
        when(orderRepository.countByCustomer(7L)).thenReturn(0L);

        PageResult<Order> result = service.listByCustomer(7L, 0, 500);

        verify(orderRepository).findByCustomer(7L, 0L, 20L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void 查看他人订单被拒绝() {
        when(orderRepository.findByNumber(any())).thenReturn(Optional.of(order(2002L, "ORD-1")));

        assertThatThrownBy(() -> service.getByNumber(1001L, "ORD-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 不存在的订单返回未找到() {
        when(orderRepository.findByNumber(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByNumber(1001L, "ORD-404"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 已创建的订单可以取消() {
        when(orderRepository.findByNumber(any()))
                .thenReturn(Optional.of(order(1001L, "ORD-1", OrderStatus.CREATED)));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.changeStatus(1001L, "ORD-1", OrderStatus.CANCELLED).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 已完成或已取消的订单不能再变更() {
        when(orderRepository.findByNumber(any()))
                .thenReturn(Optional.of(order(1001L, "ORD-9", OrderStatus.COMPLETED)));

        assertThatThrownBy(() -> service.changeStatus(1001L, "ORD-9", OrderStatus.CANCELLED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 不能把订单改回原状态() {
        when(orderRepository.findByNumber(any()))
                .thenReturn(Optional.of(order(1001L, "ORD-1", OrderStatus.CREATED)));

        assertThatThrownBy(() -> service.changeStatus(1001L, "ORD-1", OrderStatus.CREATED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void optimisticLockConflictIsPropagatedToCaller() {
        when(orderRepository.findByNumber(any()))
                .thenReturn(Optional.of(order(1001L, "ORD-1", OrderStatus.CREATED)));
        when(orderRepository.save(any()))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.CONFLICT,
                                "order was modified by another operation, please retry: ORD-1"));

        assertThatThrownBy(() -> service.changeStatus(1001L, "ORD-1", OrderStatus.CANCELLED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private static CatalogItem item(String sku, String name, String price, boolean active) {
        return new CatalogItem(1L, sku, name, new BigDecimal(price), active);
    }

    private static Order order(Long customerId, String orderNumber) {
        return order(customerId, orderNumber, OrderStatus.CREATED);
    }

    private static Order order(Long customerId, String orderNumber, OrderStatus status) {
        Instant now = Instant.now();
        return Order.rehydrate(
                null,
                new OrderNumber(orderNumber),
                customerId,
                status,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                now,
                now,
                0);
    }
}
