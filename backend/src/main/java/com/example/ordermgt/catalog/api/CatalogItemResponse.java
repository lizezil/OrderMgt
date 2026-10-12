package com.example.ordermgt.catalog.api;

import java.math.BigDecimal;

public record CatalogItemResponse(String sku, String name, BigDecimal unitPrice) {}
