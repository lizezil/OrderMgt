package com.example.ordermgt.order.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The customer-facing order identifier. Distinct from the internal surrogate id.
 */
public record OrderNumber(String value) {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    public OrderNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("order number is required");
        }
        if (value.length() > 32) {
            throw new IllegalArgumentException("order number too long");
        }
    }

    public static OrderNumber generate() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return new OrderNumber("ORD" + STAMP.format(Instant.now()) + suffix);
    }
}
