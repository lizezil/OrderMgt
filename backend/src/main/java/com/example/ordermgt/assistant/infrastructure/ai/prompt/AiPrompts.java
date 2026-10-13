package com.example.ordermgt.assistant.infrastructure.ai.prompt;

/**
 * Every piece of prose the assistant sends to the model lives here, so the wording can be found and
 * changed without hunting through the adapters.
 *
 * <p>Only the text lives here: the class that owns the data still assembles it into these templates
 * (the live catalog rows by {@code CatalogGroundingProvider}), so a prompt stays a single edit
 * without dragging the data it is filled with along with it.
 */
public final class AiPrompts {

    /** The standing system prompt, prepended to every model call. */
    public static final String SYSTEM =
            """
            You are an order management assistant. You may call tools to query or change
            a customer's orders.
            Rules:
            - Use only the provided tools; do not invent tools or arguments.
            - Mutating tools (create / cancel / change status) are only "proposals"; the
              real execution happens after the customer confirms, so when calling them say
              in the text what you intend to do.
            - Never guess or fabricate order numbers, skus or amounts; placing an order
              fills in only sku and quantity.
            - Answer in concise English.
            """;

    /**
     * Header before the live catalog. Injected so the model copies a real sku instead of inventing
     * one (ADR-0006).
     */
    public static final String CATALOG_HEADER = "Available items (take skus exactly from here):";

    /** One catalog row: sku, name, unit price, then a newline. */
    public static final String CATALOG_ROW = "- %s %s unit price %s\n";

    private AiPrompts() {}
}
