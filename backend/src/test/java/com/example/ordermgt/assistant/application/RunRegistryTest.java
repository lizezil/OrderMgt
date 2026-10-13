package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 运行态注册表只持有"活的东西"（ADR-0012/ADR-0014）——正在跑的 Run 的取消信号与当前累积文本； 读侧真相仍在库里。停止靠它
 * trip，重订阅靠它拿快照，进程重启它自行清空。
 *
 * <p>这一层不碰 DB、不碰线程：只钉注册表自己的契约——取消、订阅、快照、收尾。
 */
class RunRegistryTest {

    private final RunRegistry registry = new RunRegistry();

    /** A subscriber that records what it was told, so delivery can be asserted in order. */
    private static final class Recorder implements RunRegistry.Handle.Subscriber {

        private final List<String> deltas = new ArrayList<>();
        private boolean completed;

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void completed() {
            completed = true;
        }
    }

    @Test
    void openingARunMakesItFindableAndClosingForgetsIt() {
        RunRegistry.Handle handle = registry.open(11L);

        assertThat(handle.runId()).isEqualTo(11L);
        assertThat(registry.find(11L)).contains(handle);
        assertThat(registry.activeCount()).isEqualTo(1);

        registry.close(11L);

        assertThat(registry.find(11L)).isEmpty();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void cancellingARunFlipsItsSignalAndAnAbsentRunReportsFalse() {
        RunRegistry.Handle handle = registry.open(11L);

        assertThat(handle.isCancelled()).isFalse();
        assertThat(registry.cancel(11L)).isTrue();

        assertThat(handle.isCancelled()).isTrue();
        assertThat(handle.cancellation().isCancelled()).isTrue();
        // Cancelling again is idempotent, and cancelling a run this process is not running is a
        // reported false -- the caller decides whether that is a no-op or a 404.
        assertThat(registry.cancel(11L)).isTrue();
        assertThat(registry.cancel(999L)).isFalse();
    }

    @Test
    void proseReachesEveryCurrentSubscriber() {
        RunRegistry.Handle handle = registry.open(11L);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        handle.attach(first);
        handle.attach(second);

        handle.append("你有 ");
        handle.append("3 张订单。");

        assertThat(first.deltas).containsExactly("你有 ", "3 张订单。");
        assertThat(second.deltas).containsExactly("你有 ", "3 张订单。");
        assertThat(handle.text()).isEqualTo("你有 3 张订单。");
    }

    /** 迟到的订阅者从"当前累积文本"起步，再往后收增量——快照 + 增量，不是逐 delta 缓存。 */
    @Test
    void aLateSubscriberStartsFromTheTextSoFarThenGetsDeltas() {
        RunRegistry.Handle handle = registry.open(11L);
        handle.append("前半句，");

        Recorder late = new Recorder();
        String snapshot = handle.attach(late);

        assertThat(snapshot).isEqualTo("前半句，");
        assertThat(late.deltas).isEmpty(); // the snapshot is not replayed as deltas

        handle.append("后半句。");
        assertThat(late.deltas).containsExactly("后半句。");
    }

    @Test
    void detachingStopsDeliveryToThatSubscriberOnly() {
        RunRegistry.Handle handle = registry.open(11L);
        Recorder leaving = new Recorder();
        Recorder staying = new Recorder();
        handle.attach(leaving);
        handle.attach(staying);

        handle.detach(leaving);
        handle.append("还在说");

        assertThat(leaving.deltas).isEmpty();
        assertThat(staying.deltas).containsExactly("还在说");
    }

    @Test
    void closingTellsEverySubscriberAndDropsLateOnesImmediately() {
        RunRegistry.Handle handle = registry.open(11L);
        Recorder early = new Recorder();
        handle.attach(early);

        registry.close(11L);

        assertThat(early.completed).isTrue();
        // A subscriber that joins after the run ended is closed at once, so no connection is held
        // open on a run that will never speak again.
        Recorder late = new Recorder();
        handle.attach(late);
        assertThat(late.completed).isTrue();
        assertThat(handle.isFinished()).isTrue();
    }

    @Test
    void aDeltaAfterCloseIsIgnored() {
        RunRegistry.Handle handle = registry.open(11L);
        Recorder recorder = new Recorder();
        handle.attach(recorder);

        registry.close(11L);
        handle.append("太晚了");

        assertThat(recorder.deltas).isEmpty();
        assertThat(handle.text()).isEmpty();
    }
}
