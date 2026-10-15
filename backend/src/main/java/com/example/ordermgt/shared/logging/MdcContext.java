package com.example.ordermgt.shared.logging;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;

/**
 * The four ids that make one request traceable -- the request ({@code traceId}), the caller ({@code
 * customerId}), the conversation ({@code conversationId}) and the turn ({@code runId}) -- together
 * with the two operations that keep them attached to the work as it moves between threads.
 *
 * <p>MDC is thread-local, so a pool worker starts with nothing. A task handed to a worker either
 * carries the submitter's snapshot ({@link #carry(Runnable)}) or its own ({@link #carry(Map,
 * Runnable)}); running the task replaces exactly these four keys and restores the worker's previous
 * state afterwards, however the task ends -- which is what keeps a nested carrier (the next queued
 * turn dispatched by a finishing one) from smearing ids between turns.
 *
 * <p>Scope ownership: the request filter opens {@code traceId}/{@code customerId} and clears the
 * whole map when the request ends; {@code AssistantService} puts and removes the turn-scoped pair
 * ({@code conversationId}/{@code runId}). Nothing else writes here.
 */
public final class MdcContext {

    public static final String TRACE_ID = "traceId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String RUN_ID = "runId";

    /** Every key this system threads through logs; carrying and restoring act on exactly these. */
    private static final List<String> KEYS =
            List.of(TRACE_ID, CUSTOMER_ID, CONVERSATION_ID, RUN_ID);

    private MdcContext() {}

    /** A null value removes the key: absent is how "this fact does not apply yet" reads. */
    public static void put(String key, Object value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, String.valueOf(value));
        }
    }

    public static void remove(String... keys) {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    /** The correlation ids present on this thread right now, for replaying elsewhere. */
    public static Map<String, String> snapshot() {
        Map<String, String> context = new HashMap<>();
        for (String key : KEYS) {
            String value = MDC.get(key);
            if (value != null) {
                context.put(key, value);
            }
        }
        return Map.copyOf(context);
    }

    /** Captures this thread's ids now and replays them on whichever thread runs the task. */
    public static Runnable carry(Runnable delegate) {
        return carry(snapshot(), delegate);
    }

    /**
     * Runs {@code delegate} with exactly {@code context}'s four ids -- keys the context does not
     * carry are cleared for the duration, so the task never wears a stale id from whichever thread
     * happens to run it -- and restores the previous state when it returns.
     */
    public static Runnable carry(Map<String, String> context, Runnable delegate) {
        return () -> {
            Map<String, String> previous = snapshot();
            for (String key : KEYS) {
                put(key, context.get(key));
            }
            try {
                delegate.run();
            } finally {
                for (String key : KEYS) {
                    put(key, previous.get(key));
                }
            }
        };
    }
}
