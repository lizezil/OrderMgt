package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.RunCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The runs this process is actually executing, keyed by run id: what a stop cancels and what a
 * reattach subscribes to (ADR-0012, ADR-0014). The database stays the read-side truth; this
 * registry only holds the live handles -- the cancellation signal and the text seen so far -- so a
 * restart empties it and no state is derived from it.
 *
 * <p>It is deliberately not a cache of deltas: a handle keeps the current accumulated text (the
 * same fact {@code SpringAiAdapter} holds on its {@code StreamedTurn}), never per-token history, so
 * replaying it to a late subscriber is stateless, not resume (ADR-0009).
 */
@Slf4j
@Component
public class RunRegistry {

    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    /** Opens the handle for a run that just started; the caller closes it when the run ends. */
    public Handle open(Long runId) {
        Handle handle = new Handle(runId);
        handles.put(runId, handle);
        return handle;
    }

    public Optional<Handle> find(Long runId) {
        return Optional.ofNullable(handles.get(runId));
    }

    /**
     * Ends the run's presence: no subscriber gets another delta, every subscriber is told the run
     * closed, and the handle is dropped.
     */
    public void close(Long runId) {
        Handle handle = handles.remove(runId);
        if (handle != null) {
            handle.finish();
        }
    }

    /**
     * Trips the run's cancellation signal. Returns false when the run is not running here (already
     * terminal, or owned by another process) -- the caller decides whether that is a no-op or a
     * 404.
     */
    public boolean cancel(Long runId) {
        Handle handle = handles.get(runId);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    /** How many runs this process is executing; for tests and diagnostics. */
    public int activeCount() {
        return handles.size();
    }

    /**
     * A live run. Appends are delivered to whoever is subscribed right now; the accumulated text is
     * the current snapshot a late subscriber (or a reattach) starts from.
     */
    public static final class Handle {

        /**
         * A listener on a live run's prose. Told when the run ends so it can close its transport.
         */
        public interface Subscriber {
            void delta(String text);

            void completed();
        }

        private final Long runId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final StringBuilder text = new StringBuilder();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private boolean finished;

        private Handle(Long runId) {
            this.runId = runId;
        }

        public Long runId() {
            return runId;
        }

        /** The signal handed to the model call so a stop can abort it (ADR-0012). */
        public RunCancellation cancellation() {
            return cancelled::get;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void cancel() {
            cancelled.set(true);
        }

        /** Appends prose and pushes it to every current subscriber. Called on the stream thread. */
        public synchronized void append(String delta) {
            if (finished) {
                return;
            }
            text.append(delta);
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                notifyDelta(subscriber, delta);
            }
        }

        /**
         * Snapshot + subscribe in one critical section: the returned text is everything seen so far
         * and no delta can slip between producing it and registering the subscriber -- the
         * reattach's first frame is exactly the text up to the moment it joined.
         *
         * <p>A run that already ended delivers {@link Subscriber#completed()} immediately, so the
         * caller never holds a connection open on a run that will never speak again.
         */
        public synchronized String attach(Subscriber subscriber) {
            if (finished) {
                notifyCompleted(subscriber);
                return text.toString();
            }
            subscribers.add(subscriber);
            return text.toString();
        }

        public synchronized void detach(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

        /** The current accumulated text; a snapshot, never a per-delta cache. */
        public synchronized String text() {
            return text.toString();
        }

        public synchronized boolean isFinished() {
            return finished;
        }

        private synchronized void finish() {
            finished = true;
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                notifyCompleted(subscriber);
            }
            subscribers.clear();
        }

        private static void notifyDelta(Subscriber subscriber, String delta) {
            try {
                subscriber.delta(delta);
            } catch (RuntimeException ex) {
                // One dead subscriber must not stop the prose reaching the others.
                log.debug("run subscriber dropped a delta: {}", ex.getMessage());
            }
        }

        private static void notifyCompleted(Subscriber subscriber) {
            try {
                subscriber.completed();
            } catch (RuntimeException ex) {
                log.debug("run subscriber could not be closed: {}", ex.getMessage());
            }
        }
    }
}
