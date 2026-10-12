package com.example.ordermgt.order.infrastructure;

import com.example.ordermgt.order.domain.Money;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Row and domain mapping for orders.
 *
 * <p>The value objects travel as primitives: an {@link OrderNumber} is stored as text, a {@link
 * Money} as a decimal and the status as its enum name. The conversions live here as small default
 * methods so every caller sees the same rule.
 *
 * <p>Rebuilding the aggregate is the one thing MapStruct cannot generate: {@link Order} derives its
 * total from its lines and its lines come from a second query, so {@code toDomain} takes them as an
 * argument and calls the domain's own {@code rehydrate} factory.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OrderPersistenceMapper {

    @Mapping(target = "orderNo", source = "orderNumber")
    OrderPO toPo(Order order);

    // lineAmount() is derived, not a record component, so MapStruct cannot see it as a source
    // property -- it has to be spelled out (same reason as OrderApiMapper).
    @Mapping(
            target = "lineAmount",
            expression = "java(line.lineAmount() == null ? null : line.lineAmount().amount())")
    OrderLinePO toPo(OrderLine line);

    OrderLine toDomain(OrderLinePO po);

    default Order toDomain(OrderPO po, List<OrderLine> lines) {
        return Order.rehydrate(
                po.getId(),
                new OrderNumber(po.getOrderNo()),
                po.getCustomerId(),
                OrderStatus.valueOf(po.getStatus()),
                lines,
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

    default String map(OrderNumber number) {
        return number == null ? null : number.value();
    }

    default String map(OrderStatus status) {
        return status == null ? null : status.name();
    }

    default BigDecimal map(Money money) {
        return money == null ? null : money.amount();
    }

    default Money map(BigDecimal amount) {
        return amount == null ? null : Money.of(amount);
    }
}
