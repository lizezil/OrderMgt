package com.example.ordermgt.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermgt.shared.logging.MdcContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Business rule: 流式池必须有界——并发达到上限且等待队列填满时立刻拒绝，而不是像 {@code newFixedThreadPool}
 * 那样无限排队。上限与队列大小由属性给出，这里用小值把边界逼出来。
 *
 * <p>另一条：工人线程执行任务时带着提交线程的关联标识（US-8.5），跑完还原——池里借来的线程不留下上一个 请求的笔迹。
 */
class AsyncConfigTest {

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        MDC.clear();
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

    @Test
    void thePoolCarriesTheSubmittersIdsOntoTheWorkerAndLeavesNoLeakBehind() throws Exception {
        executor = (ThreadPoolExecutor) new AsyncConfig().assistantStreamExecutor(1, 1);

        MDC.clear();
        MdcContext.put(MdcContext.TRACE_ID, "t-pool");
        CountDownLatch firstRan = new CountDownLatch(1);
        String[] firstSaw = new String[2];
        executor.execute(
                () -> {
                    firstSaw[0] = MDC.get(MdcContext.TRACE_ID);
                    firstSaw[1] = Thread.currentThread().getName();
                    firstRan.countDown();
                });
        assertThat(firstRan.await(5, TimeUnit.SECONDS)).isTrue();
        // 请求线程结束了：它自己的清理是 filter 的事，这里模拟清理之后提交第二个任务。
        MDC.clear();

        CountDownLatch secondRan = new CountDownLatch(1);
        String[] secondSaw = new String[1];
        executor.execute(
                () -> {
                    secondSaw[0] = MDC.get(MdcContext.TRACE_ID);
                    secondRan.countDown();
                });
        assertThat(secondRan.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(firstSaw[0]).isEqualTo("t-pool");
        assertThat(firstSaw[1]).startsWith("assistant-stream");
        // 同一个工人线程：上一个任务的标识没有漏给下一个。
        assertThat(secondSaw[0]).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
