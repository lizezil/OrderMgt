package com.example.ordermgt.assistant.domain;

import java.util.Objects;

/**
 * The whitelist entry for one tool.
 *
 * <p>Every tool carries a handler, because every tool has to be executable by *someone*. {@code
 * mutating} decides who that someone is: a readonly handler is called by {@code AssistantService}
 * straight away, a mutating handler is reached only through {@code ActionConfirmationService},
 * after a customer confirmed the PendingAction (ADR-0001). The flag is the single source of truth
 * for that split.
 *
 * <p>A mutating tool additionally carries a {@link ToolCard}: everything the customer is shown
 * about the proposal, decided here beside the schema and the handler rather than in a separate
 * switch keyed on the tool name.
 */
public record ToolSpec(
        String name,
        String description,
        String inputSchemaJson,
        boolean mutating,
        ToolHandler handler,
        ToolCard card) {

    public ToolSpec {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson is required");
        Objects.requireNonNull(handler, "handler is required for tool " + name);
        if (mutating && card == null) {
            throw new IllegalArgumentException("mutating tool " + name + " needs a card");
        }
        if (!mutating && card != null) {
            throw new IllegalArgumentException("readonly tool " + name + " must not have a card");
        }
    }

    public static ToolSpec readonly(
            String name, String description, String schema, ToolHandler handler) {
        return new ToolSpec(name, description, schema, false, handler, null);
    }

    public static ToolSpec mutating(
            String name, String description, String schema, ToolCard card, ToolHandler handler) {
        return new ToolSpec(name, description, schema, true, handler, card);
    }

    /** One line for the card. Only meaningful for mutating tools. */
    public String summarise(String argumentsJson) {
        return card == null ? name : card.summary().apply(argumentsJson);
    }
}
