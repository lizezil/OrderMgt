package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.ToolSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes a whitelisted {@link ToolSpec} to the model as a tool *definition* only.
 *
 * The model is never allowed to execute anything: internal tool execution is disabled
 * (see SpringAiAdapter), so this callback only has to describe the schema. Execution
 * happens later, in AssistantService, where the customer id is available.
 */
@RequiredArgsConstructor
public class RegistryToolCallback implements ToolCallback {

    private final ToolSpec spec;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchemaJson())
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException(
                "tool execution is controlled by AssistantService, not by the model");
    }
}
