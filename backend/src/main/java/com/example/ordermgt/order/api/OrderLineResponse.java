package com.example.ordermgt.order.api;

import java.math.BigDecimal;

public record OrderLineResponse(String sku, String itemName, int quantity,
                                BigDecimal unitPrice, BigDecimal lineAmount) {
}
