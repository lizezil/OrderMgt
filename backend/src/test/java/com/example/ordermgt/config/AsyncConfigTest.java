package com.example.ordermgt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 流式池必须有界——并发达到上限且等待队列填满时立刻拒绝，而不是像 {@code newFixedThreadPool}
 * 那样无限排队。上限与队列大小由属性给出，这里用小值把边界逼出来。
 */
class AsyncConfigTest {

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsOnceThreadsAndQueueAreBothFull() {
        executor = (ThreadPoolExecutor) new AsyncConfig().assistantStreamExecutor(2, 3);

        assertThat(executor.getMaximumPoolSize()).isEqualTo(2);
        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(3);

        CountDownLatch release = new CountDownLatch(1);
        // Capacity is 2 running + 3 queued = 5; the 6th submission has nowhere to go.
        for (int i = 0; i < 5; i++) {
            executor.execute(() -> await(release));
        }
        assertThat(executor.getQueue().size()).isEqualTo(3);

        assertThatThrownBy(() -> executor.execute(() -> await(release)))
                .isInstanceOf(RejectedExecutionException.class);

        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
