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
 * <p>The flag also decides which half of the customer-facing text the tool carries, and the two are
 * mutually exclusive: a mutating tool carries a {@link ToolCard} (what the proposal would do) and a
 * readonly tool carries a {@link ToolReport} (what the query found). Either way it is decided here
 * beside the schema and the handler rather than in a separate switch keyed on the tool name, so
 * adding a tool stays a single edit. A mutating tool carries no report because its result is shown
 * by the card, never by the reply.
 */
public record ToolSpec(
        String name,
        String description,
        String inputSchemaJson,
        boolean mutating,
        ToolHandler handler,
        ToolCard card,
        ToolReport report) {

    public ToolSpec {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson is required");
        Objects.requireNonNull(handler, "handler is required for tool " + name);
        if (mutating && card == null) {
            throw new IllegalArgumentException("mutating tool " + name + " needs a card");
        }
        if (mutating && report != null) {
            throw new IllegalArgumentException(
                    "mutating tool " + name + " must not have a report: its card shows the result");
        }
        if (!mutating && card != null) {
            throw new IllegalArgumentException("readonly tool " + name + " must not have a card");
        }
        if (!mutating && report == null) {
            throw new IllegalArgumentException(
                    "readonly tool " + name + " needs a report to answer the customer with");
        }
    }

    public static ToolSpec readonly(
            String name,
            String description,
            String schema,
            ToolReport report,
            ToolHandler handler) {
        return new ToolSpec(name, description, schema, false, handler, null, report);
    }

    public static ToolSpec mutating(
            String name, String description, String schema, ToolCard card, ToolHandler handler) {
        return new ToolSpec(name, description, schema, true, handler, card, null);
    }

    /** One line for the card. Only meaningful for mutating tools. */
    public String summarise(String argumentsJson) {
        return card == null ? name : card.summary().apply(argumentsJson);
    }
}
