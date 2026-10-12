package com.example.ordermgt.order.application;

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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for orders. Note that no external (AI) call ever happens inside these
 * methods — see ADR-0002.
 */
@RequiredArgsConstructor
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogRepository catalogRepository;

    @Transactional
    public Order createOrder(Long customerId, List<LineCommand> commands) {
        return orderRepository.save(Order.create(customerId, resolveLines(commands)));
    }

    @Transactional(readOnly = true)
    public PageResult<Order> listByCustomer(Long customerId, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = size <= 0 || size > 100 ? 20 : size;
        List<Order> items =
                orderRepository.findByCustomer(customerId, (safePage - 1) * safeSize, safeSize);
        long total = orderRepository.countByCustomer(customerId);
        return PageResult.of(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public Order getByNumber(Long customerId, String orderNumber) {
        Order order =
                orderRepository
                        .findByNumber(new OrderNumber(orderNumber))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "订单不存在: " + orderNumber));
        if (!order.getCustomerId().equals(customerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该订单");
        }
        return order;
    }

    @Transactional
    public Order changeStatus(Long customerId, String orderNumber, OrderStatus target) {
        Order order = getByNumber(customerId, orderNumber);
        if (!order.canTransitionTo(target)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "订单 " + orderNumber + " 当前状态 " + order.getStatus() + " 不能变更为 " + target);
        }
        order.transitionTo(target);
        return orderRepository.save(order);
    }

    /** Prices are resolved here, server-side, from the catalog. */
    private List<OrderLine> resolveLines(List<LineCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "订单至少需要一行商品");
        }
        List<OrderLine> lines = new ArrayList<>();
        for (LineCommand command : commands) {
            CatalogItem item =
                    catalogRepository
                            .findBySku(command.sku())
                            .filter(CatalogItem::active)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_FAILED,
                                                    "未知或已下架商品: " + command.sku()));
            try {
                lines.add(
                        OrderLine.of(
                                item.sku(), item.name(), command.quantity(), item.unitPrice()));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
            }
        }
        return lines;
    }
}
