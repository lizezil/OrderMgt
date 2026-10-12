package com.example.ordermgt.order.domain;

import java.time.Instant;
import java.util.List;
import lombok.Getter;

/** Aggregate root. Lines are immutable once created; only the status moves. */
@Getter
public class Order {

    private Long id;
    private final OrderNumber orderNumber;
    private final Long customerId;
    private OrderStatus status;
    private final List<OrderLine> lines;
    private final Money totalAmount;
    private final Instant createdAt;
    private Instant updatedAt;

    private Order(
            Long id,
            OrderNumber orderNumber,
            Long customerId,
            OrderStatus status,
            List<OrderLine> lines,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.status = status;
        this.lines = List.copyOf(lines);
        this.totalAmount =
                this.lines.stream().map(OrderLine::lineAmount).reduce(Money.ZERO, Money::plus);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Order create(Long customerId, List<OrderLine> lines) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        Instant now = Instant.now();
        return new Order(
                null, OrderNumber.generate(), customerId, OrderStatus.CREATED, lines, now, now);
    }

    public static Order rehydrate(
            Long id,
            OrderNumber orderNumber,
            Long customerId,
            OrderStatus status,
            List<OrderLine> lines,
            Instant createdAt,
            Instant updatedAt) {
        return new Order(id, orderNumber, customerId, status, lines, createdAt, updatedAt);
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return status.canTransitionTo(target);
    }

    public void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition " + status + " -> " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }
}
