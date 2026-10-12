package com.example.ordermgt.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(String orderNo, String status, BigDecimal totalAmount,
                            List<OrderLineResponse> lines, Instant createdAt, Instant updatedAt) {
}
