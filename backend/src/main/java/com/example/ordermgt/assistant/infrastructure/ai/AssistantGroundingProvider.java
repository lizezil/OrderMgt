package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The facts the assistant is handed before every model call: the live catalog, and the order this
 * conversation most recently placed.
 *
 * <p>The catalog half is what stops the model inventing skus ("Widget" instead of "WIDGET-01"), and
 * its list is capped twice -- by row count and by characters -- because an unbounded catalog would
 * eventually push every call over the model's context window: the assistant would not degrade, it
 * would stop answering at all. When the cap bites, the truncation is declared so the model treats
 * the list as a partial view.
 *
 * <p>The conversation half is what lets the assistant answer "查看刚刚这个订单" about the order the
 * customer just confirmed. That order number exists in the service (on the confirmed action) but
 * not in the transcript -- the card shows the outcome and no message records it -- so without this
 * line the model can only list everything and hope the newest entry is the one being asked about.
 */
@Component
public class AssistantGroundingProvider implements GroundingProvider {

    private final CatalogRepository catalogRepository;
    private final PendingActionRepository pendingActionRepository;
    private final int maxRows;
    private final int maxChars;

    public AssistantGroundingProvider(
            CatalogRepository catalogRepository,
            PendingActionRepository pendingActionRepository,
            @Value("${assistant.ai.grounding.max-rows:200}") int maxRows,
            @Value("${assistant.ai.grounding.max-chars:12000}") int maxChars) {
        this.catalogRepository = catalogRepository;
        this.pendingActionRepository = pendingActionRepository;
        this.maxRows = maxRows;
        this.maxChars = maxChars;
    }

    @Override
    public String facts(Long customerId, Long conversationId) {
        StringBuilder sb = new StringBuilder(catalog());
        pendingActionRepository
                .findLatestConfirmed(customerId, conversationId)
                .ifPresent(action -> appendLastConfirmed(sb, action));
        return sb.toString();
    }

    /** A confirmed action: what was authorised, and the order number it produced. */
    private static void appendLastConfirmed(StringBuilder sb, PendingAction action) {
        sb.append(AiPrompts.LAST_CONFIRMED_ORDER_HEADER)
                .append('\n')
                .append(
                        String.format(
                                AiPrompts.LAST_CONFIRMED_ORDER_ROW,
                                action.getResultRef(),
                                action.getSummary()));
    }

    private String catalog() {
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
