package com.example.ordermgt.order.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderRepository;
import com.example.ordermgt.order.domain.OrderStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderLineMapper lineMapper;

    public OrderRepositoryImpl(OrderMapper orderMapper, OrderLineMapper lineMapper) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
    }

    @Override
    public Order save(Order order) {
        OrderPO po = new OrderPO();
        po.setId(order.getId());
        po.setOrderNo(order.getOrderNumber().value());
        po.setCustomerId(order.getCustomerId());
        po.setStatus(order.getStatus().name());
        po.setTotalAmount(order.getTotalAmount().amount());
        po.setCreatedAt(order.getCreatedAt());
        po.setUpdatedAt(order.getUpdatedAt());

        if (order.getId() == null) {
            orderMapper.insert(po);
            order.assignId(po.getId());
            for (OrderLine line : order.getLines()) {
                OrderLinePO lpo = new OrderLinePO();
                lpo.setOrderId(po.getId());
                lpo.setSku(line.sku());
                lpo.setItemName(line.itemName());
                lpo.setQuantity(line.quantity());
                lpo.setUnitPrice(line.unitPrice().amount());
                lpo.setLineAmount(line.lineAmount().amount());
                lineMapper.insert(lpo);
            }
        } else {
            orderMapper.updateById(po);
        }
        return order;
    }

    @Override
    public Optional<Order> findByNumber(OrderNumber orderNumber) {
        return Optional.ofNullable(orderMapper.selectOne(Wrappers.<OrderPO>lambdaQuery()
                        .eq(OrderPO::getOrderNo, orderNumber.value())))
                .map(this::toDomain);
    }

    @Override
    public List<Order> findByCustomer(Long customerId, long offset, long limit) {
        long safeSize = limit > 0 ? limit : 20;
        long current = (offset / safeSize) + 1;
        Page<OrderPO> page = orderMapper.selectPage(new Page<>(current, safeSize),
                Wrappers.<OrderPO>lambdaQuery()
                        .eq(OrderPO::getCustomerId, customerId)
                        .orderByDesc(OrderPO::getCreatedAt));
        return page.getRecords().stream().map(this::toDomain).toList();
    }

    @Override
    public long countByCustomer(Long customerId) {
        return orderMapper.selectCount(Wrappers.<OrderPO>lambdaQuery()
                .eq(OrderPO::getCustomerId, customerId));
    }

    private Order toDomain(OrderPO po) {
        List<OrderLine> lines = lineMapper.selectList(Wrappers.<OrderLinePO>lambdaQuery()
                        .eq(OrderLinePO::getOrderId, po.getId())
                        .orderByAsc(OrderLinePO::getId))
                .stream()
                .map(l -> OrderLine.of(l.getSku(), l.getItemName(), l.getQuantity(), l.getUnitPrice()))
                .toList();
        return Order.rehydrate(po.getId(), new OrderNumber(po.getOrderNo()), po.getCustomerId(),
                OrderStatus.valueOf(po.getStatus()), lines, po.getCreatedAt(), po.getUpdatedAt());
    }
}
