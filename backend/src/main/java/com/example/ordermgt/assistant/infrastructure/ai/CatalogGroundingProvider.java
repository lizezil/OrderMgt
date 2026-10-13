package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Grounds the model in the real catalog. Without this it invents skus ("Widget" instead of
 * "WIDGET-01") and the createOrder call then fails validation.
 */
@RequiredArgsConstructor
@Component
public class CatalogGroundingProvider implements GroundingProvider {

    private final CatalogRepository catalogRepository;

    @Override
    public String facts() {
        StringBuilder sb = new StringBuilder("Available items (take skus exactly from here):\n");
        for (CatalogItem item : catalogRepository.findAllActive()) {
            sb.append("- ")
                    .append(item.sku())
                    .append(' ')
                    .append(item.name())
                    .append(" unit price ")
                    .append(item.unitPrice())
                    .append('\n');
        }
        return sb.toString();
    }
}
