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

    /**
     * The clock region every customer-visible time is told in: the order number stamps it, and the
     * assistant renders an order's placed-at with it too. Kept here so the two can never drift
     * apart by eight hours.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);

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
