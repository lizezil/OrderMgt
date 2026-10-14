package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ConversationEventBus;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.security.CurrentCustomer;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The customer's own status stream (ADR-0014): one long-lived SSE carrying the sidebar's world, so
 * a turn finishing in another conversation updates the badge without a refresh or a poll.
 *
 * <p>It opens with a `snapshot` -- every conversation of the customer, archived ones included --
 * then only increments: `state` (a conversation's derived state or run id changed) and `queue` (its
 * waiting line changed), with `ping` keeping the connection alive. A reconnect re-sends the
 * snapshot and the client replaces its list wholesale: there is no {@code Last-Event-ID} resume,
 * because that would need the server to cache events, which ADR-0009 already refused.
 *
 * <p>This is a GET with the customer in a header, so it is read with {@code fetch} + a stream
 * rather than {@code EventSource} (which cannot set headers) -- the same arrangement the chat
 * stream uses.
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant/events")
public class EventsController {

    /**
     * A long ceiling rather than "no timeout": the stream is meant to outlive any single turn, and
     * the heartbeat keeps it warm well within this. A client that outlives it simply reconnects and
     * gets a fresh snapshot, exactly as it does after a dropped network.
     */
    private static final long STREAM_TIMEOUT_MS = 3_600_000L;

    private final ConversationService conversationService;
    private final ConversationEventBus bus;
    private final AssistantApiMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * How the emitter is created. An injection seam only: a container-less test hands in a mock so
     * it can read the first frame off the wire, which an emitter the framework owns cannot be made
     * to yield (its buffered sends flush on an async dispatch this test does not drive).
     */
    private final Supplier<SseEmitter> emitters;

    @Autowired
    public EventsController(
            ConversationService conversationService,
            ConversationEventBus bus,
            AssistantApiMapper mapper,
            ObjectMapper objectMapper) {
        this(
                conversationService,
                bus,
                mapper,
                objectMapper,
                () -> new SseEmitter(STREAM_TIMEOUT_MS));
    }

    EventsController(
            ConversationService conversationService,
            ConversationEventBus bus,
            AssistantApiMapper mapper,
            ObjectMapper objectMapper,
            Supplier<SseEmitter> emitters) {
        this.conversationService = conversationService;
        this.bus = bus;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.emitters = emitters;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@CurrentCustomer Long customerId) {
        SseEmitter emitter = emitters.get();
        // Register before the snapshot so no increment can slip into the gap between the two: a
        // frame that arrives early is harmless (the snapshot replaces the list), one that arrives
        // after the snapshot but before registration would be lost.
        if (!bus.register(customerId, emitter)) {
            throw new BusinessException(
                    ErrorCode.SERVICE_BUSY,
                    "too many open status streams for this customer; close one and retry");
        }
        try {
            List<ConversationResponse> snapshot =
                    conversationService.listFor(customerId, true).stream()
                            .map(mapper::toResponse)
                            .toList();
            emitter.send(
                    SseEmitter.event()
                            .name("snapshot")
                            .data(objectMapper.writeValueAsString(snapshot)));
        } catch (JsonProcessingException ex) {
            // A snapshot that cannot be written is OUR bug, not a dead client: unregister and fail
            // loudly, rather than holding a connection open that will never receive anything.
            bus.unregister(customerId, emitter);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "could not serialize the status snapshot");
        } catch (IOException | IllegalStateException ex) {
            // The client left before it could be told anything; the emitter callbacks clean up.
            log.debug("status stream closed before its snapshot: {}", ex.getMessage());
        }
        return emitter;
    }
}
