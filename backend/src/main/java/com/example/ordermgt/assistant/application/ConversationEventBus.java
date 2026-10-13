package com.example.ordermgt.assistant.application;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The per-customer status stream behind {@code GET /api/assistant/events} (ADR-0014): one
 * long-lived SSE connection per customer, state changes fanned out to every open connection.
 *
 * <p>It deliberately does NOT run on the turn pool. {@code assistantStreamExecutor} is a bounded
 * pool of "turns with a beginning and an end"; a page-level connection that sits open holding a
 * worker thread would let a few tabs starve real turns. Here a subscription is just a registered
 * emitter -- no thread is held -- and the only repeating work is a heartbeat on its own single
 * thread.
 *
 * <p>In-process by construction: the registry is a map, so the fan-out does not cross instances.
 * That is the same single-instance assumption the rest of the run layer makes, and it is recorded
 * as a known gap, not hidden.
 */
@Slf4j
@Component
public class ConversationEventBus {

    private final Map<Long, Set<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final int maxPerCustomer;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "assistant-events-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });

    public ConversationEventBus(
            @Value("${assistant.events.heartbeat-ms:15000}") long heartbeatMs,
            @Value("${assistant.events.max-connections-per-customer:4}") int maxPerCustomer) {
        this.maxPerCustomer = maxPerCustomer;
        // A non-positive interval turns the heartbeat off, which is what a test wants: no daemon
        // thread ticking behind a contract test that never opens a real connection.
        if (heartbeatMs > 0) {
            this.heartbeat.scheduleAtFixedRate(
                    this::ping, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Registers an emitter for the customer. Returns false when the customer already has the
     * permitted number of live streams -- the caller answers rather than silently dropping one. A
     * closed/timed-out/errored connection cleans itself up through the emitter callbacks.
     */
    public boolean register(Long customerId, SseEmitter emitter) {
        Set<SseEmitter> set =
                connections.computeIfAbsent(customerId, id -> ConcurrentHashMap.newKeySet());
        synchronized (set) {
            if (set.size() >= maxPerCustomer) {
                return false;
            }
            set.add(emitter);
        }
        emitter.onCompletion(() -> drop(customerId, emitter));
        emitter.onTimeout(() -> drop(customerId, emitter));
        emitter.onError(error -> drop(customerId, emitter));
        return true;
    }

    /**
     * One frame to every connection the customer has open; a dead one is cleaned up, not retried.
     */
    public void publish(Long customerId, String event, String data) {
        Set<SseEmitter> set = connections.get(customerId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException ex) {
                log.debug("dropping a status stream that stopped listening: {}", ex.getMessage());
                drop(customerId, emitter);
            }
        }
    }

    /** Drops a connection explicitly, e.g. when the snapshot could not be written. */
    public void unregister(Long customerId, SseEmitter emitter) {
        drop(customerId, emitter);
    }

    /** How many connections a customer has open; for tests and diagnostics. */
    public int connectionsOf(Long customerId) {
        Set<SseEmitter> set = connections.get(customerId);
        return set == null ? 0 : set.size();
    }

    private void ping() {
        for (Long customerId : connections.keySet()) {
            publish(customerId, "ping", "{}");
        }
    }

    private void drop(Long customerId, SseEmitter emitter) {
        Set<SseEmitter> set = connections.get(customerId);
        if (set == null) {
            return;
        }
        synchronized (set) {
            set.remove(emitter);
            if (set.isEmpty()) {
                connections.remove(customerId, set);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }
}
