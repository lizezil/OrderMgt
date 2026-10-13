package com.example.ordermgt.assistant.application;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serialises turns per conversation: one runs, the rest wait in line (ADR-0012). The invariant "at
 * most one RUNNING Run per conversation" is the same fact as "this conversation's slot is taken",
 * so both entry points take their slot here rather than each inventing its own guard -- otherwise a
 * blocking turn and a streaming turn could still overlap.
 *
 * <p>The two transports treat a taken slot differently, on purpose. Streaming has a connection to
 * wait on, so it queues FIFO and starts when the turn ahead of it finishes. The blocking path
 * carries an {@code Idempotency-Key} and promises "run now or not at all", so it refuses with
 * {@code CONFLICT} -- making it wait would turn an atomic operation into a long one. Neither
 * difference changes the slot: it is taken the same way in both.
 *
 * <p>All of this lives in memory. A queued item has no row in the database, no {@code runs} entry
 * and no memory of its own, so a restart empties the queue and leaves no orphan behind -- the
 * accepted trade for not persisting scheduling state (the design's §9 known gap).
 *
 * <p>Single instance only: the slot is enforced per process, and two instances would each keep
 * their own. That is the same limitation as the rest of the runtime registry.
 */
@Slf4j
@Component
public class ConversationRunQueue {

    /**
     * The work a queued item owns. {@link #start} runs on the streaming pool when the item reaches
     * the front; {@link #drop} tells its client the item was removed before it ever ran, so the
     * connection does not hang waiting for a turn that will never come.
     */
    public interface Turn {

        void start();

        void drop();
    }

    /** What happened to a streaming submit: it is running now, or it is waiting at a position. */
    public sealed interface Admission {

        record Started() implements Admission {}

        /** {@code position} is 1-based, counted from the front of this conversation's line. */
        record Queued(String itemId, int position) implements Admission {}
    }

    private final Executor executor;
    private final int depth;

    /** Guarded by {@code this}: the whole map, every slot and every wait line. */
    private final Map<Long, Slot> conversations = new HashMap<>();

    private final AtomicLong itemIds = new AtomicLong();

    public ConversationRunQueue(
            @Qualifier("assistantStreamExecutor") Executor executor,
            @Value("${assistant.run.queue-depth:5}") int depth) {
        this.executor = executor;
        this.depth = depth;
    }

    /**
     * Admits a streaming turn: starts it if the conversation is idle, queues it otherwise.
     *
     * @throws BusinessException {@code QUEUE_FULL} (429) when this conversation's line is already
     *     at {@code assistant.run.queue-depth}. A full line is a capacity fact, so the caller gets
     *     the one code that says "retry later" instead of the "state conflict" a 409 would imply.
     */
    public Admission submitStreaming(Long conversationId, Turn turn) {
        synchronized (this) {
            Slot slot = conversations.computeIfAbsent(conversationId, id -> new Slot());
            if (slot.running) {
                if (slot.waiting.size() >= depth) {
                    throw new BusinessException(ErrorCode.QUEUE_FULL);
                }
                Item item = new Item(nextItemId(), turn);
                slot.waiting.addLast(item);
                return new Admission.Queued(item.id(), slot.waiting.size());
            }
            slot.running = true;
        }
        // Dispatched outside the lock: the pool may run it immediately, and a Turn must never run
        // while holding the registry's monitor.
        try {
            executor.execute(turn::start);
        } catch (RuntimeException ex) {
            release(conversationId);
            throw ex;
        }
        return new Admission.Started();
    }

    /**
     * Runs a blocking turn under the same invariant: it takes the slot, or it is refused.
     *
     * @throws BusinessException {@code CONFLICT} (409) when a turn is already in flight for this
     *     conversation. A {@code null} conversationId is a brand-new conversation, which by
     *     definition has no run to collide with.
     */
    public <T> T runBlocking(Long conversationId, Supplier<T> work) {
        if (conversationId == null) {
            return work.get();
        }
        synchronized (this) {
            Slot slot = conversations.computeIfAbsent(conversationId, id -> new Slot());
            if (slot.running) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "this conversation already has a run in flight");
            }
            slot.running = true;
        }
        try {
            return work.get();
        } finally {
            finished(conversationId);
        }
    }

    /**
     * Releases the slot and hands it to the next waiting turn, if any. Called when a turn reaches a
     * terminal state on either transport; a conversation nobody submitted to is a no-op, which
     * keeps the direct {@code streamTurn} seam callable without admission.
     */
    public void finished(Long conversationId) {
        for (; ; ) {
            Turn next = takeNext(conversationId);
            if (next == null) {
                return;
            }
            try {
                executor.execute(next::start);
                return;
            } catch (RuntimeException ex) {
                // The pool refused the hand-off. Tell that item's client rather than pinning it at
                // the front forever, and keep draining the ones behind it.
                log.warn(
                        "could not start the next queued turn for conversation {}: {}",
                        conversationId,
                        ex.getMessage());
                drop(next);
            }
        }
    }

    /**
     * Removes a still-waiting item and tells its client. Removed means never run: the turn the
     * customer deleted is not executed at all, not executed late.
     *
     * @return whether an item with that id was waiting; false also covers the race where the item
     *     reached the front and started between the customer's click and this call
     */
    public boolean remove(Long conversationId, String itemId) {
        Turn dropped = null;
        synchronized (this) {
            Slot slot = conversations.get(conversationId);
            if (slot != null) {
                Iterator<Item> waiting = slot.waiting.iterator();
                while (waiting.hasNext()) {
                    Item item = waiting.next();
                    if (item.id().equals(itemId)) {
                        waiting.remove();
                        dropped = item.turn();
                        break;
                    }
                }
            }
        }
        if (dropped == null) {
            return false;
        }
        drop(dropped);
        return true;
    }

    /** Whether a turn is in flight for this conversation right now. */
    public boolean isBusy(Long conversationId) {
        synchronized (this) {
            Slot slot = conversations.get(conversationId);
            return slot != null && slot.running;
        }
    }

    /**
     * Hands back the next waiting turn, leaving the slot held for it; null means the line is empty
     * and the conversation goes back to idle.
     */
    private Turn takeNext(Long conversationId) {
        synchronized (this) {
            Slot slot = conversations.get(conversationId);
            if (slot == null) {
                return null;
            }
            Item next = slot.waiting.pollFirst();
            if (next == null) {
                conversations.remove(conversationId);
                return null;
            }
            return next.turn();
        }
    }

    /**
     * Gives up a slot whose dispatch failed: nothing can run, so every waiter is told rather than
     * left in a line with no one to serve it.
     */
    private void release(Long conversationId) {
        for (; ; ) {
            Turn next = takeNext(conversationId);
            if (next == null) {
                return;
            }
            drop(next);
        }
    }

    private void drop(Turn turn) {
        try {
            turn.drop();
        } catch (RuntimeException ex) {
            log.warn("could not tell a client its queued turn was dropped: {}", ex.getMessage());
        }
    }

    private String nextItemId() {
        return "q" + itemIds.incrementAndGet();
    }

    /** One conversation's execution slot plus the turns waiting behind it. */
    private static final class Slot {

        private boolean running;

        private final Deque<Item> waiting = new ArrayDeque<>();
    }

    private record Item(String id, Turn turn) {}
}
