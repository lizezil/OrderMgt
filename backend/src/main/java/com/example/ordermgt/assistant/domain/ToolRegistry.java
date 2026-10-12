package com.example.ordermgt.assistant.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whitelist. Anything the model returns that is not registered here is rejected --
 * we never reflect on a tool name or fall back to running it.
 *
 * Framework-free on purpose (this is the domain ring): it is built from the injected
 * {@link ToolSpec} list and wired as a bean by the infrastructure layer, not annotated here.
 */
public class ToolRegistry {

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

    public ToolRegistry(List<ToolSpec> definitions) {
        for (ToolSpec spec : definitions) {
            specs.put(spec.name(), spec);
        }
    }

    public List<ToolSpec> all() {
        return List.copyOf(specs.values());
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(specs.get(name));
    }
}
