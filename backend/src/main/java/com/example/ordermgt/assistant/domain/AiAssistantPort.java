package com.example.ordermgt.assistant.domain;

import java.util.List;
import java.util.function.Consumer;

/**
 * Port: the business layer talks to the model only through this. Spring AI lives behind it (see
 * ADR-0003).
 *
 * <p>Note that streaming is expressed as "callback + final result", not as a reactive type: the
 * domain must not have to know which HTTP client the model happens to live behind.
 */
public interface AiAssistantPort {

    /**
     * @param memory the conversation so far, oldest first, with the current customer message last.
     *     Replayed verbatim as the model's memory.
     */
    AssistantTurn respond(Long customerId, List<ChatMessage> memory);

    /**
     * Same turn, but text arrives incrementally through {@code onTextDelta}. The returned turn is
     * the finished one -- tool calls included -- so callers can treat it exactly like {@link
     * #respond}.
     */
    AssistantTurn respondStreaming(
            Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta);
}
