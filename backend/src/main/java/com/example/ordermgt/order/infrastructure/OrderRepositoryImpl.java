package com.example.ordermgt.order.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ordermgt.order.domain.Money;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderFilter;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
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
            // Optimistic lock: the update carries the version the aggregate was read at, and the
            // WHERE clause rejects the write if anyone committed a change since. That is what keeps
            // two racing status transitions from both winning -- the plain read in changeStatus
            // (unlike confirm's SELECT ... FOR UPDATE) locks nothing, so the check and the write
            // have to be tied together by the version.
            Integer validated = po.getVersion();
            po.setVersion(validated + 1);
            int updated =
                    orderMapper.update(
                            po,
                            Wrappers.<OrderPO>lambdaUpdate()
                                    .eq(OrderPO::getId, po.getId())
                                    .eq(OrderPO::getVersion, validated));
            if (updated == 0) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "order was modified by another operation, please retry: "
                                + po.getOrderNo());
            }
            order.assignVersion(validated + 1);
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
    public List<Order> findByCustomer(
            Long customerId, OrderFilter filter, long offset, long limit) {
        long safeSize = limit > 0 ? limit : 20;
        long current = (offset / safeSize) + 1;
        Page<OrderPO> page =
                orderMapper.selectPage(
                        new Page<>(current, safeSize),
                        filterOf(customerId, filter).orderByDesc(OrderPO::getCreatedAt));
        return page.getRecords().stream().map(this::toDomain).toList();
    }

    @Override
    public long countByCustomer(Long customerId, OrderFilter filter) {
        return orderMapper.selectCount(filterOf(customerId, filter));
    }

    @Override
    public Money sumAmountsByCustomer(Long customerId, OrderFilter filter) {
        List<Long> hitIds =
                orderMapper.selectList(filterOf(customerId, filter).select(OrderPO::getId)).stream()
                        .map(OrderPO::getId)
                        .toList();
        if (hitIds.isEmpty()) {
            return Money.ZERO;
        }
        // 合计从明细快照现算，和列表里逐行显示的那些数字同源（US-4.2）：读 orders.total_amount 更省事，
        // 但那是一列写进去就没人再读的数据，汇总挂在它身上等于把一句话建在一份没人核对过的账上。
        BigDecimal total =
                lineMapper
                        .selectList(
                                Wrappers.<OrderLinePO>lambdaQuery()
                                        .in(OrderLinePO::getOrderId, hitIds)
                                        .select(OrderLinePO::getLineAmount))
                        .stream()
                        .map(OrderLinePO::getLineAmount)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Money.of(total);
    }

    /** 客户 id 与收窄条件合成一个查询：身份不是条件，它每次都在。 */
    private static LambdaQueryWrapper<OrderPO> filterOf(Long customerId, OrderFilter filter) {
        LambdaQueryWrapper<OrderPO> query =
                Wrappers.<OrderPO>lambdaQuery().eq(OrderPO::getCustomerId, customerId);
        if (filter.status() != null) {
            query.eq(OrderPO::getStatus, filter.status().name());
        }
        if (filter.fromInclusive() != null) {
            query.ge(OrderPO::getCreatedAt, filter.fromInclusive());
        }
        if (filter.toExclusive() != null) {
            query.lt(OrderPO::getCreatedAt, filter.toExclusive());
        }
        return query;
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
