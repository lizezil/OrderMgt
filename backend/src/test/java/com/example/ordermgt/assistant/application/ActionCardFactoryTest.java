package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.infrastructure.ai.tools.OrderToolDefinitions;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.application.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The card is what the customer reads before authorising, so its rows must name business things --
 * not the tool's raw argument keys -- and must show what the order will cost: unit price, line
 * total and the order total, all resolved server-side from the catalog.
 *
 * <p>The layout now lives on each tool's own card, so these tests run against the real definitions
 * from {@link OrderToolDefinitions} rather than a hand-built registry.
 */
class ActionCardFactoryTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final ActionCardFactory factory =
            new ActionCardFactory(
                    new ToolRegistry(
                            new OrderToolDefinitions()
                                    .orderToolSpecs(
                                            mock(OrderService.class),
                                            new ObjectMapper(),
                                            catalog())),
                    new ObjectMapper());

    @Test
    void createOrderNamesEachLineAsProductQuantityAndPrice() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":3}]}"));

        assertThat(card.title()).isEqualTo("Create Order");
        assertThat(card.summary()).isEqualTo("Place order: WIDGET-01 × 3");
        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "WIDGET-01"),
                        new ActionCard.Field("Quantity", "3"),
                        new ActionCard.Field("Unit price", "¥19.90"),
                        new ActionCard.Field("Line total", "¥59.70"),
                        new ActionCard.Field("Total", "¥59.70"));
    }

    @Test
    void everyOrderedLineIsShownWithItsOwnPriceAndTheTotalSumsThem() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":3},{\"sku\":\"GIZMO-03\",\"quantity\":2}]}"));

        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "WIDGET-01"),
                        new ActionCard.Field("Quantity", "3"),
                        new ActionCard.Field("Unit price", "¥19.90"),
                        new ActionCard.Field("Line total", "¥59.70"),
                        new ActionCard.Field("Item", "GIZMO-03"),
                        new ActionCard.Field("Quantity", "2"),
                        new ActionCard.Field("Unit price", "¥5.00"),
                        new ActionCard.Field("Line total", "¥10.00"),
                        // 59.70 + 10.00, and it is the catalog's arithmetic, not the model's.
                        new ActionCard.Field("Total", "¥69.70"));
    }

    /** A sku the catalog does not carry must not collapse the card into raw argument keys. */
    @Test
    void anUnknownSkuShowsEmptyAmountsRatherThanHidingTheLine() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"NOPE-99\",\"quantity\":1}]}"));

        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "NOPE-99"),
                        new ActionCard.Field("Quantity", "1"),
                        new ActionCard.Field("Unit price", "—"),
                        new ActionCard.Field("Line total", "—"),
                        new ActionCard.Field("Total", "—"));
    }

    /**
     * One bad line makes the whole total unstateable: a partial sum would understate the charge.
     */
    @Test
    void oneUnpriceableLineMakesTheTotalUnknown() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":1},{\"sku\":\"NOPE-99\",\"quantity\":1}]}"));

        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "WIDGET-01"),
                        new ActionCard.Field("Quantity", "1"),
                        new ActionCard.Field("Unit price", "¥19.90"),
                        new ActionCard.Field("Line total", "¥19.90"),
                        new ActionCard.Field("Item", "NOPE-99"),
                        new ActionCard.Field("Quantity", "1"),
                        new ActionCard.Field("Unit price", "—"),
                        new ActionCard.Field("Line total", "—"),
                        new ActionCard.Field("Total", "—"));
    }

    /** A zero line would otherwise quote ¥0.00 and read as a free item instead of a bad request. */
    @Test
    void aNonPositiveQuantityHasNoLineTotal() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":0}]}"));

        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "WIDGET-01"),
                        new ActionCard.Field("Quantity", "0"),
                        new ActionCard.Field("Unit price", "¥19.90"),
                        new ActionCard.Field("Line total", "—"),
                        new ActionCard.Field("Total", "—"));
    }

    @Test
    void statusChangeNamesTheOrderAndTargetStatus() {
        ActionCard card =
                factory.card(
                        action(
                                "updateOrderStatus",
                                "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"CANCELLED\"}"));

        assertThat(card.title()).isEqualTo("Change Order Status");
        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Order number", "ORD-1"),
                        new ActionCard.Field("Target status", "CANCELLED"));
    }

    @Test
    void anUndescribableProposalStillBecomesARejectableCard() {
        ActionCard card = factory.card(action("createOrder", "not-json"));

        assertThat(card.fields()).containsExactly(new ActionCard.Field("Arguments", "not-json"));
    }

    private PendingAction action(String toolName, String argumentsJson) {
        return PendingAction.propose(1001L, 7L, toolName, argumentsJson, "s", TTL);
    }

    /** The real prices the Flyway seed carries; a sku absent from here reads as unknown. */
    private static CatalogRepository catalog() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findBySku("WIDGET-01"))
                .thenReturn(
                        Optional.of(
                                new CatalogItem(
                                        1L, "WIDGET-01", "Widget", new BigDecimal("19.90"), true)));
        when(catalog.findBySku("GIZMO-03"))
                .thenReturn(
                        Optional.of(
                                new CatalogItem(
                                        3L, "GIZMO-03", "Gizmo", new BigDecimal("5.00"), true)));
        return catalog;
    }
}
