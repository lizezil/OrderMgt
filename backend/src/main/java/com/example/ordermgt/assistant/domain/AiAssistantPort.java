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
     * @param conversationId which conversation the turn belongs to; grounding needs it because the
     *     order "we just placed" is a fact about a conversation, not about a whole customer.
     * @param memory the conversation so far, oldest first, with the current customer message last.
     *     Replayed verbatim as the model's memory.
     */
    AssistantTurn respond(Long customerId, Long conversationId, List<ChatMessage> memory);

    /**
     * Same turn, but text arrives incrementally through {@code onTextDelta}. The returned turn is
     * the finished one -- tool calls included -- so callers can treat it exactly like {@link
     * #respond}.
     *
     * <p>{@code cancellation} is polled as chunks arrive; when it flips the upstream stream is
     * aborted and {@link RunCancelledException} carries the half text produced so far (ADR-0012).
     * Callers that cannot be stopped pass {@link RunCancellation#NEVER}.
     */
    AssistantTurn respondStreaming(
            Long customerId,
            Long conversationId,
            List<ChatMessage> memory,
            Consumer<String> onTextDelta,
            RunCancellation cancellation);
}
