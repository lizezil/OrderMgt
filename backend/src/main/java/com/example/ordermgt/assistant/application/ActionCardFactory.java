package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Assembles the confirmation card for a PendingAction.
 *
 * All tool-specific knowledge -- the title, the one-line summary and the labelled rows --
 * comes from the tool's own card ({@code ToolSpec.card()}), so this class holds no switch on
 * the tool name. What stays here is the envelope (token, endpoints, expiry, status) and the
 * fallback: a proposal we cannot describe must still become a card the customer can reject,
 * not a 500.
 */
@Component
public class ActionCardFactory {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ActionCardFactory(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public ActionCard card(PendingAction action) {
        String toolName = action.getToolName();
        String argumentsJson = action.getArgumentsJson();
        return new ActionCard(
                action.getToken(),
                toolName,
                titleOf(toolName),
                summaryOf(toolName, argumentsJson),
                argumentsJson,
                fields(toolName, argumentsJson),
                "/api/assistant/actions/" + action.getToken() + "/confirm",
                "/api/assistant/actions/" + action.getToken() + "/reject",
                action.getExpiresAt(),
                action.getStatus().name());
    }

    /**
     * Safe to call on anything the model produced: a proposal we cannot describe must still
     * become a card the customer can reject, not a 500.
     */
    public String summaryOf(String toolName, String argumentsJson) {
        return toolRegistry.find(toolName)
                .map(spec -> {
                    try {
                        return spec.summarise(argumentsJson);
                    } catch (RuntimeException ex) {
                        return toolName;
                    }
                })
                .orElse(toolName);
    }

    private String titleOf(String toolName) {
        return toolRegistry.find(toolName)
                .filter(spec -> spec.card() != null)
                .map(spec -> spec.card().title())
                .orElse(toolName);
    }

    /** The tool's own rows when it has a card and can read the arguments; raw keys otherwise. */
    private List<ActionCard.Field> fields(String toolName, String argumentsJson) {
        List<ActionCard.Field> presented = toolRegistry.find(toolName)
                .filter(spec -> spec.card() != null)
                .map(spec -> presentedFields(spec, argumentsJson))
                .orElse(List.of());
        return presented.isEmpty() ? rawFields(argumentsJson) : presented;
    }

    private List<ActionCard.Field> presentedFields(ToolSpec spec, String argumentsJson) {
        try {
            return spec.card().fields().apply(argumentsJson).stream()
                    .map(field -> new ActionCard.Field(field.label(), field.value()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<ActionCard.Field> rawFields(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(argumentsJson);
        } catch (Exception ex) {
            return List.of(new ActionCard.Field("参数", argumentsJson));
        }
        if (!root.isObject()) {
            return List.of(new ActionCard.Field("参数", argumentsJson));
        }
        List<ActionCard.Field> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> entries = root.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode value = entry.getValue();
            fields.add(new ActionCard.Field(entry.getKey(),
                    value.isValueNode() ? value.asText() : value.toString()));
        }
        return fields;
    }
}
