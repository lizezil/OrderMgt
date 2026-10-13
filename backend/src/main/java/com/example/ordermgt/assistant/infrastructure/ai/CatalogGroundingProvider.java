package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
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
        StringBuilder sb = new StringBuilder(AiPrompts.CATALOG_HEADER).append('\n');
        for (CatalogItem item : catalogRepository.findAllActive()) {
            sb.append(
                    String.format(
                            AiPrompts.CATALOG_ROW, item.sku(), item.name(), item.unitPrice()));
        }
        return sb.toString();
    }
}
