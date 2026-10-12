package com.example.ordermgt.catalog.domain;

import java.math.BigDecimal;

public record CatalogItem(Long id, String sku, String name, BigDecimal unitPrice, boolean active) {}
