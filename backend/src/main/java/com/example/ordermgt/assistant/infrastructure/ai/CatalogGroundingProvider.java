package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Grounds the model in the real catalog. Without this it invents skus ("Widget" instead of
 * "WIDGET-01") and the createOrder call then fails validation.
 *
 * <p>The list is capped twice -- by row count and by characters -- because an unbounded catalog
 * would eventually push every call over the model's context window: the assistant would not
 * degrade, it would stop answering at all. When the cap bites, the truncation is declared so the
 * model treats the list as a partial view.
 */
@Component
public class CatalogGroundingProvider implements GroundingProvider {

    private final CatalogRepository catalogRepository;
    private final int maxRows;
    private final int maxChars;

    public CatalogGroundingProvider(
            CatalogRepository catalogRepository,
            @Value("${assistant.ai.grounding.max-rows:200}") int maxRows,
            @Value("${assistant.ai.grounding.max-chars:12000}") int maxChars) {
        this.catalogRepository = catalogRepository;
        this.maxRows = maxRows;
        this.maxChars = maxChars;
    }

    @Override
    public String facts() {
        StringBuilder sb = new StringBuilder(AiPrompts.CATALOG_HEADER).append('\n');
        int rows = 0;
        for (CatalogItem item : catalogRepository.findAllActive()) {
            if (rows >= maxRows) {
                return (sb.append(AiPrompts.CATALOG_TRUNCATED).toString());
            }
            String row =
                    String.format(AiPrompts.CATALOG_ROW, item.sku(), item.name(), item.unitPrice());
            if (sb.length() + row.length() > maxChars) {
                return sb.append(AiPrompts.CATALOG_TRUNCATED).toString();
            }
            sb.append(row);
            rows++;
        }
        return sb.toString();
    }
}
