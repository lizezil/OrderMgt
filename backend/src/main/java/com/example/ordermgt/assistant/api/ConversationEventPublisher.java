package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ConversationEventBus;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.ConversationView;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns "something about a conversation changed" into an increment on the customer's status stream
 * (ADR-0014). The state it pushes is derived exactly as the REST list derives it -- one source of
 * truth, so a pushed badge always agrees with a refreshed page.
 *
 * <p>Controllers call this after the write they performed; nothing in the application layer knows
 * about the stream, which keeps the fan-out a presentation concern instead of threading a bus
 * through the domain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationEventPublisher {

    static final String STATE = "state";
    static final String QUEUE = "queue";

    private final ConversationService conversationService;
    private final ConversationRunQueue runQueue;
    private final ConversationEventBus bus;
    private final AssistantApiMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Re-derives one conversation's state and pushes it to every open connection of the customer.
     */
    public void stateChanged(Long customerId, Long conversationId) {
        if (customerId == null || conversationId == null) {
            return;
        }
        try {
            ConversationView view = conversationService.view(customerId, conversationId);
            publish(customerId, STATE, mapper.toResponse(view));
        } catch (BusinessException ex) {
            // The conversation is not this customer's, or no longer exists: nothing to push, and a
            // status stream must never turn a stale notification into an error on someone's page.
            log.debug("no state to push for conversation {}: {}", conversationId, ex.getMessage());
        }
    }

    /** Pushes the conversation's waiting line, front first, after an admission or a removal. */
    public void queueChanged(Long customerId, Long conversationId) {
        if (customerId == null || conversationId == null) {
            return;
        }
        List<QueuedTurnResponse> items =
                runQueue.waiting(conversationId).stream().map(mapper::toResponse).toList();
        publish(customerId, QUEUE, new QueueEventResponse(conversationId, items));
    }

    private void publish(Long customerId, String event, Object payload) {
        try {
            bus.publish(customerId, event, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize a {} event: {}", event, ex.getMessage());
        }
    }
}
