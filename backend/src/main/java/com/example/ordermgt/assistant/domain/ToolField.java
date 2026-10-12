package com.example.ordermgt.assistant.domain;

/**
 * One business-labelled row on a confirmation card ("商品" / "WIDGET-01"), never the tool's
 * raw argument key. Structural and view-agnostic: it names a business thing and its value,
 * not a widget.
 */
public record ToolField(String label, String value) {
}
