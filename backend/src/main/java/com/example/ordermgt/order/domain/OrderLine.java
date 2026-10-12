package com.example.ordermgt.order.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record OrderLine(String sku, String itemName, int quantity, Money unitPrice) {

    public OrderLine {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        Objects.requireNonNull(itemName, "itemName is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public static OrderLine of(String sku, String itemName, int quantity, BigDecimal unitPrice) {
        return new OrderLine(sku, itemName, quantity, Money.of(unitPrice));
    }

    public Money lineAmount() {
        return unitPrice.multiply(quantity);
    }
}
