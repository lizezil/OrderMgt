package com.example.ordermgt.order.domain;

import java.time.Instant;

/**
 * 只读查询的收窄条件：状态、下单时间区间。任一为空即不收窄——{@link #NONE} 就是全部都不收窄。
 *
 * <p>时间用左闭右开（{@code [fromInclusive, toExclusive)}）：调用方把一个自然日翻成"从当天零点到次日零点"， 边界就落在一个瞬时上，不必去决定"当天
 * 23:59:59.999 算不算"。
 *
 * <p>条件只在这位客户名下的订单里筛（US-6.1）：客户 id 不是这里的字段，而是每次查询自带的那个。
 */
public record OrderFilter(OrderStatus status, Instant fromInclusive, Instant toExclusive) {

    public static final OrderFilter NONE = new OrderFilter(null, null, null);

    public boolean isEmpty() {
        return status == null && fromInclusive == null && toExclusive == null;
    }
}
