package com.example.ordermgt.assistant.application;

/**
 * A readonly tool that ran: the payload the model is shown and the audit keeps, and the
 * customer-facing text the server rendered from it (see {@code ToolReport}).
 *
 * <p>The reply is built from {@code rendered}, never from {@code resultJson}: the payload stays the
 * machine contract, the report is what a customer can read, and a model turn that produced no prose
 * of its own still leaves the customer a readable answer.
 */
public record ExecutedTool(String name, String resultJson, String rendered) {}
