package com.example.ordermgt.assistant.domain;

/**
 * A tool call the model asked for. Whether it may run is decided by the whitelist
 * (ToolSpec.mutating), never by the model.
 */
public record ProposedToolCall(String name, String argumentsJson, boolean mutating) {
}
