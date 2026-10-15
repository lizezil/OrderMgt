package com.example.ordermgt.shared.logging;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A bounded pool that hands each task the submitting thread's correlation ids ({@link
 * MdcContext#carry(Runnable)}), so a line written on a worker still names the request it belongs
 * to.
 *
 * <p>This covers the direct hand-off (request thread submits a turn). It is deliberately only half
 * of the propagation story: a task a finishing worker dispatches on behalf of another request (the
 * next turn in a conversation's line) must carry the <b>submitter's</b> snapshot with it
 * explicitly, because the decorator here would otherwise stamp it with the finishing worker's
 * context -- see {@code ConversationRunQueue}.
 *
 * <p>Shape: fixed thread count over a bounded {@link ArrayBlockingQueue} with {@link AbortPolicy},
 * because saturation must surface to the caller (see {@code AsyncConfig}), not be absorbed.
 */
public class MdcPropagatingExecutor extends ThreadPoolExecutor {

    public MdcPropagatingExecutor(int threads, int queueCapacity, String threadNamePrefix) {
        super(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, threadNamePrefix);
                    thread.setDaemon(true);
                    return thread;
                },
                new AbortPolicy());
    }

    @Override
    public void execute(Runnable command) {
        super.execute(MdcContext.carry(command));
    }
}
