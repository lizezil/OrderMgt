package com.example.ordermgt.assistant.domain;

/**
 * What a tool handler produced.
 *
 * {@code payloadJson} is the full result: what the model is shown for a readonly tool, and
 * what the caller gets back for a mutating one. {@code ref} is the one stable identifier the
 * action produced, if any (an order number, say) -- kept separately so a replayed
 * confirmation can point at the thing that was made without re-reading the payload.
 *
 * Carrying the ref as a field is the point: the confirmation service must not have to know
 * that {@code createOrder} happens to nest its order number under a key called "orderNo".
 */
public record ToolResult(String payloadJson, String ref) {

    public static ToolResult of(String payloadJson) {
        return new ToolResult(payloadJson, null);
    }

    public static ToolResult of(String payloadJson, String ref) {
        return new ToolResult(payloadJson, ref == null || ref.isBlank() ? null : ref);
    }
}
