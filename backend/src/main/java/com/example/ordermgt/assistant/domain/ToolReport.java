package com.example.ordermgt.assistant.domain;

/**
 * How the *server* describes a readonly tool's result to the customer.
 *
 * <p>The mirror of {@link ToolCard}: a card says what a proposal would do, a report says what a
 * query found. It lives on the tool for the same reason the card does -- adding a tool stays a
 * single edit, and the customer-facing text is decided from server-side knowledge of the payload
 * rather than asked of the model, which may answer with no prose at all.
 *
 * <p>The input is the tool's own payload JSON, so a report is a pure projection of the contract the
 * model already sees: no second data path, and a describer testable by handing it a payload. Shape
 * follows {@link ToolHandler}: one behaviour, carried by the tool that owns it.
 */
@FunctionalInterface
public interface ToolReport {

    /** The customer-facing text for what this tool returned. */
    String describe(String payloadJson);
}
