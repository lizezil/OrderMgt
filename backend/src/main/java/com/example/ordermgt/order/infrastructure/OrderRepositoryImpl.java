package com.example.ordermgt.order.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderLineMapper lineMapper;
    private final OrderPersistenceMapper persistenceMapper;

    @Override
    public Order save(Order order) {
        OrderPO po = persistenceMapper.toPo(order);

        if (order.getId() == null) {
            orderMapper.insert(po);
            order.assignId(po.getId());
            for (OrderLine line : order.getLines()) {
                OrderLinePO lpo = persistenceMapper.toPo(line);
                lpo.setOrderId(po.getId());
                lineMapper.insert(lpo);
            }
        } else {
            orderMapper.updateById(po);
        }
        return order;
    }

    @Override
    public Optional<Order> findByNumber(OrderNumber orderNumber) {
        return Optional.ofNullable(
                        orderMapper.selectOne(
                                Wrappers.<OrderPO>lambdaQuery()
                                        .eq(OrderPO::getOrderNo, orderNumber.value())))
                .map(this::toDomain);
    }

    @Override
    public List<Order> findByCustomer(Long customerId, long offset, long limit) {
        long safeSize = limit > 0 ? limit : 20;
        long current = (offset / safeSize) + 1;
        Page<OrderPO> page =
                orderMapper.selectPage(
                        new Page<>(current, safeSize),
                        Wrappers.<OrderPO>lambdaQuery()
                                .eq(OrderPO::getCustomerId, customerId)
                                .orderByDesc(OrderPO::getCreatedAt));
        return page.getRecords().stream().map(this::toDomain).toList();
    }

    @Override
    public long countByCustomer(Long customerId) {
        return orderMapper.selectCount(
                Wrappers.<OrderPO>lambdaQuery().eq(OrderPO::getCustomerId, customerId));
    }

    private Order toDomain(OrderPO po) {
        List<OrderLine> lines =
                lineMapper
                        .selectList(
                                Wrappers.<OrderLinePO>lambdaQuery()
                                        .eq(OrderLinePO::getOrderId, po.getId())
                                        .orderByAsc(OrderLinePO::getId))
                        .stream()
                        .map(persistenceMapper::toDomain)
                        .toList();
        return persistenceMapper.toDomain(po, lines);
    }
}
