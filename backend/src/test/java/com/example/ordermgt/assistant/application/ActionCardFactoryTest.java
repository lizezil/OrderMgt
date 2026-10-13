package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.infrastructure.ai.tools.OrderToolDefinitions;
import com.example.ordermgt.order.application.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The card is what the customer reads before authorising, so its rows must name business things --
 * not the tool's raw argument keys.
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
                                    .orderToolSpecs(mock(OrderService.class), new ObjectMapper())),
                    new ObjectMapper());

    @Test
    void createOrderNamesEachLineAsProductAndQuantity() {
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
                        new ActionCard.Field("Quantity", "3"));
    }

    @Test
    void everyOrderedLineIsShown() {
        ActionCard card =
                factory.card(
                        action(
                                "createOrder",
                                "{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":3},{\"sku\":\"GIZMO-03\",\"quantity\":2}]}"));

        assertThat(card.fields())
                .containsExactly(
                        new ActionCard.Field("Item", "WIDGET-01"),
                        new ActionCard.Field("Quantity", "3"),
                        new ActionCard.Field("Item", "GIZMO-03"),
                        new ActionCard.Field("Quantity", "2"));
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
}
