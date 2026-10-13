package com.example.ordermgt.order.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** The customer-facing order identifier. Distinct from the internal surrogate id. */
public record OrderNumber(String value) {

    /** order_number is VARCHAR(32). */
    public static final int MAX_LENGTH = 32;

    private static final int SUFFIX_LENGTH = 6;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    public OrderNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("order number is required");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("order number too long");
        }
    }

    public static OrderNumber generate() {
        String suffix = UUID.randomUUID().toString().substring(0, SUFFIX_LENGTH).toUpperCase();
        return new OrderNumber("ORD" + STAMP.format(Instant.now()) + suffix);
    }
}
