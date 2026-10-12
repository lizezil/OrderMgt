package com.example.ordermgt.assistant.domain;

/**
 * Executes a tool against the business services. The customer id is passed from the request
 * context so a tool can never be talked into reading or writing another customer's data.
 *
 * Both readonly and mutating tools carry a handler; who is allowed to call it is decided by
 * {@link ToolSpec#mutating()}, not by the handler. What a handler produced -- its payload and
 * the identifier of whatever it made -- comes back as a {@link ToolResult}.
 */
@FunctionalInterface
public interface ToolHandler {

    ToolResult execute(Long customerId, String argumentsJson);
}
