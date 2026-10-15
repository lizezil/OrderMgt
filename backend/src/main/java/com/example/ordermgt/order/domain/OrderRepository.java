package com.example.ordermgt.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findByNumber(OrderNumber orderNumber);

    List<Order> findByCustomer(Long customerId, OrderFilter filter, long offset, long limit);

    long countByCustomer(Long customerId, OrderFilter filter);

    /**
     * 命中项**全部**的合计金额，不受分页影响。金额每次从明细快照现算（US-4.2），不读 {@code orders.total_amount}
     * ——那一列写进去就没人再读，读它等于把汇总绑在一份没人核对过的数据上。
     */
    Money sumAmountsByCustomer(Long customerId, OrderFilter filter);
}
