package com.example.ordermgt.assistant.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * How the *server* describes a mutating tool to the customer, as opposed to how the model describes
 * it to itself.
 *
 * <p>It lives on the tool so that adding a tool is a single edit: its title, its one-line summary
 * and its labelled rows are decided next to its schema and handler, not in a switch in another
 * layer that has to be found and updated in step. Asking the model to describe its own proposal
 * would defeat the point -- the card must be built from server-side knowledge of the tool.
 *
 * <p>Deliberately not the view model: {@code ActionCard} stays in the application layer and adds
 * the endpoints, status and expiry. This is only the part that is knowledge about the tool itself.
 */
public record ToolCard(
        String title, Function<String, String> summary, Function<String, List<ToolField>> fields) {

    public ToolCard {
        Objects.requireNonNull(title, "card title is required");
        Objects.requireNonNull(summary, "card summary is required");
        Objects.requireNonNull(fields, "card fields are required");
    }
}
