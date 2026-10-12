package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import org.springframework.stereotype.Component;

/**
 * Grounds the model in the real catalog. Without this it invents skus ("Widget"
 * instead of "WIDGET-01") and the createOrder call then fails validation.
 */
@Component
public class CatalogGroundingProvider implements GroundingProvider {

    private final CatalogRepository catalogRepository;

    public CatalogGroundingProvider(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @Override
    public String facts() {
        StringBuilder sb = new StringBuilder("可用商品（sku 必须原样取自这里）：\n");
        for (CatalogItem item : catalogRepository.findAllActive()) {
            sb.append("- ").append(item.sku())
                    .append(' ').append(item.name())
                    .append(" 单价 ").append(item.unitPrice())
                    .append('\n');
        }
        return sb.toString();
    }
}
