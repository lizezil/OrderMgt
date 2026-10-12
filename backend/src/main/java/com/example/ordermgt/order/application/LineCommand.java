package com.example.ordermgt.order.application;

/**
 * A requested line before prices are resolved. Note there is no price here: amounts always come
 * from the catalog, never from the caller (or the AI).
 */
public record LineCommand(String sku, int quantity) {}
