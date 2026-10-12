package com.example.ordermgt.catalog.domain;

import java.util.List;
import java.util.Optional;

/**
 * Port: the catalog is the single source of truth for prices.
 */
public interface CatalogRepository {

    Optional<CatalogItem> findBySku(String sku);

    List<CatalogItem> findAllActive();
}
