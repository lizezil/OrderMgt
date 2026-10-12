package com.example.ordermgt.order.api;

import com.example.ordermgt.order.domain.Money;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OrderApiMapper {

    @Mapping(target = "orderNo", source = "orderNumber")
    OrderResponse toResponse(Order order);

    // lineAmount() is derived (not a record component), so it needs an explicit expression:
    // MapStruct only treats record components as source properties.
    @Mapping(
            target = "lineAmount",
            expression = "java(line.lineAmount() == null ? null : line.lineAmount().amount())")
    OrderLineResponse toResponse(OrderLine line);

    default String map(OrderNumber number) {
        return number == null ? null : number.value();
    }

    default BigDecimal map(Money money) {
        return money == null ? null : money.amount();
    }
}
