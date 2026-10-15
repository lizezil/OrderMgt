package com.example.ordermgt.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Business rule: 关联标识跟着工作走，但不渗漏——跨线程携带的是提交那一刻的快照，跑完把借来的线程还原成它 来时的样子。线程是借的，借的东西不能留下自己的笔迹。 */
class MdcContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void snapshotCapturesOnlyTheIdsThatArePresent() {
        MdcContext.put(MdcContext.TRACE_ID, "t-1");
        MdcContext.put(MdcContext.CUSTOMER_ID, 1001L);

        assertThat(MdcContext.snapshot())
                .containsExactlyInAnyOrderEntriesOf(Map.of("traceId", "t-1", "customerId", "1001"));
    }

    @Test
    void carryReplaysTheSnapshotOnWhicheverThreadRunsTheTask() throws Exception {
        MdcContext.put(MdcContext.TRACE_ID, "t-1");
        MdcContext.put(MdcContext.CUSTOMER_ID, 1001L);
        String[] observed = new String[1];
        Runnable task =
                MdcContext.carry(
                        () ->
                                observed[0] =
                                        MDC.get(MdcContext.TRACE_ID)
                                                + "/"
                                                + MDC.get(MdcContext.CUSTOMER_ID));
        MDC.clear();

        Thread worker = new Thread(task);
        worker.start();
        worker.join();

        assertThat(observed[0]).isEqualTo("t-1/1001");
    }

    @Test
    void aStaleIdOnTheWorkerIsReplacedForTheDurationAndRestoredAfterwards() {
        MdcContext.put(MdcContext.TRACE_ID, "worker-old");
        MdcContext.put(MdcContext.RUN_ID, "run-old");
        Map<String, String> submitter =
                Map.of(MdcContext.TRACE_ID, "t-2", MdcContext.CONVERSATION_ID, "7");
        String[] during = new String[2];

        MdcContext.carry(
                        submitter,
                        () -> {
                            during[0] = MDC.get(MdcContext.TRACE_ID);
                            during[1] = MDC.get(MdcContext.RUN_ID);
                        })
                .run();

        // 运行期间恰好是提交者的四个键：提交者没有 runId，运行期间就必须没有。
        assertThat(during[0]).isEqualTo("t-2");
        assertThat(during[1]).isNull();
        // 跑完还原：线程回到它来时的样子。
        assertThat(MDC.get(MdcContext.TRACE_ID)).isEqualTo("worker-old");
        assertThat(MDC.get(MdcContext.RUN_ID)).isEqualTo("run-old");
    }

    @Test
    void theWorkerIsRestoredEvenWhenTheTaskThrows() {
        MdcContext.put(MdcContext.TRACE_ID, "worker-old");
        Map<String, String> submitter = Map.of(MdcContext.TRACE_ID, "t-2");

        assertThatThrownBy(
                        () ->
                                MdcContext.carry(
                                                submitter,
                                                () -> {
                                                    throw new IllegalStateException("boom");
                                                })
                                        .run())
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(MdcContext.TRACE_ID)).isEqualTo("worker-old");
    }
}
