package com.example.ordermgt.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findByNumber(OrderNumber orderNumber);

    List<Order> findByCustomer(Long customerId, long offset, long limit);

    long countByCustomer(Long customerId);
}
