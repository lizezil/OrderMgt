package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.ordermgt.assistant.application.ConversationRunQueue.Admission;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.logging.MdcContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * Business rule: 一个会话同时只跑一个回合——忙时后来的流式回合排队（FIFO），同步入口直接拒绝。
 *
 * <p>这一层不碰模型也不碰数据库，只钉住那条不变量本身：谁占了执行位、谁在排队、排到第几位、队伍的上限，
 * 以及"删掉一条排队项"到底意味着"它不会执行"而不是"它晚点执行"。执行位是进程内的，重启即空， 所以这里也顺带钉住"派发失败不能把会话卡死"。
 */
class ConversationRunQueueTest {

    private static final Long CONVERSATION = 7L;

    /** Dispatched but not yet run: the test decides when each turn actually starts. */
    private final List<Runnable> dispatched = new ArrayList<>();

    private ConversationRunQueue queue;

    @BeforeEach
    void setUp() {
        queue = new ConversationRunQueue(dispatched::add, 5);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void anIdleConversationStartsItsTurnInsteadOfQueueingIt() {
        RecordingTurn turn = new RecordingTurn();

        assertThat(queue.submitStreaming(CONVERSATION, turn)).isInstanceOf(Admission.Started.class);

        assertThat(queue.isBusy(CONVERSATION)).isTrue();
        // Dispatched is not started: the pool owns when it runs, not the submitting thread.
        assertThat(turn.started).isFalse();
        assertThat(dispatched).hasSize(1);
        runDispatched();
        assertThat(turn.started).isTrue();
    }

    @Test
    void aSecondTurnWaitsAndStartsWhenTheFirstOneFinishes() {
        RecordingTurn first = new RecordingTurn();
        RecordingTurn second = new RecordingTurn();
        queue.submitStreaming(CONVERSATION, first);

        Admission admission = queue.submitStreaming(CONVERSATION, second);

        assertThat(admission).isEqualTo(new Admission.Queued("q1", 1));
        runDispatched();
        assertThat(first.started).isTrue();
        assertThat(second.started).isFalse();

        queue.finished(CONVERSATION);
        runDispatched();

        assertThat(second.started).isTrue();
        assertThat(queue.isBusy(CONVERSATION)).isTrue();
        queue.finished(CONVERSATION);
        assertThat(queue.isBusy(CONVERSATION)).isFalse();
    }

    @Test
    void theLineIsServedInTheOrderItWasFormed() {
        List<String> order = new ArrayList<>();
        queue.submitStreaming(CONVERSATION, recording(order, "first"));
        for (int i = 0; i < 5; i++) {
            queue.submitStreaming(CONVERSATION, recording(order, "waiting-" + i));
        }

        while (queue.isBusy(CONVERSATION)) {
            runDispatched();
            queue.finished(CONVERSATION);
        }

        assertThat(order)
                .containsExactly(
                        "first", "waiting-0", "waiting-1", "waiting-2", "waiting-3", "waiting-4");
    }

    @Test
    void theLineHasACeilingAndTheRefusalSaysCapacityNotConflict() {
        queue.submitStreaming(CONVERSATION, new RecordingTurn());
        for (int i = 0; i < 5; i++) {
            queue.submitStreaming(CONVERSATION, new RecordingTurn());
        }

        BusinessException refusal =
                catchThrowableOfType(
                        () -> queue.submitStreaming(CONVERSATION, new RecordingTurn()),
                        BusinessException.class);

        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.QUEUE_FULL);
        // 429: a full line is "retry later", not "this state is illegal" (that would be 409).
        assertThat(refusal.errorCode().status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aBlockingTurnIsRefusedWhileAStreamingTurnRuns() {
        queue.submitStreaming(CONVERSATION, new RecordingTurn());

        assertThatThrownBy(() -> queue.runBlocking(CONVERSATION, () -> "never"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void aBlockingTurnTakesTheSlotForItsOwnDurationAndGivesItBack() {
        String result =
                queue.runBlocking(
                        CONVERSATION,
                        () -> {
                            assertThat(queue.isBusy(CONVERSATION)).isTrue();
                            return "done";
                        });

        assertThat(result).isEqualTo("done");
        assertThat(queue.isBusy(CONVERSATION)).isFalse();
    }

    @Test
    void aBlockingTurnAlsoWakesTheWaitingStreamingTurn() {
        RecordingTurn waiting = new RecordingTurn();

        // Both transports take the same slot, which is why a streaming turn waits for a blocking
        // one even though only the streaming side knows how to queue.
        queue.runBlocking(
                CONVERSATION,
                () -> {
                    assertThat(queue.submitStreaming(CONVERSATION, waiting))
                            .isInstanceOf(Admission.Queued.class);
                    return null;
                });
        runDispatched();

        assertThat(waiting.started).isTrue();
    }

    @Test
    void deletingAWaitingItemMeansItNeverRuns() {
        RecordingTurn running = new RecordingTurn();
        RecordingTurn waiting = new RecordingTurn();
        queue.submitStreaming(CONVERSATION, running);
        Admission.Queued queued = (Admission.Queued) queue.submitStreaming(CONVERSATION, waiting);

        assertThat(queue.remove(CONVERSATION, queued.itemId())).isTrue();

        assertThat(waiting.dropped).isTrue();
        assertThat(waiting.started).isFalse();
        runDispatched();
        queue.finished(CONVERSATION);
        assertThat(waiting.started).isFalse();
        assertThat(queue.isBusy(CONVERSATION)).isFalse();
    }

    @Test
    void deletingSomethingThatIsNotWaitingReportsNoSuchItem() {
        assertThat(queue.remove(CONVERSATION, "q-unknown")).isFalse();
    }

    @Test
    void aRestartedProcessInheritsNoLineAndLeavesNoOrphanBehind() {
        queue.submitStreaming(CONVERSATION, new RecordingTurn());
        String itemId =
                ((Admission.Queued) queue.submitStreaming(CONVERSATION, new RecordingTurn()))
                        .itemId();

        // The line is memory only: a restart is a new registry. Nothing survives it -- which is
        // also why nothing is left orphaned in the database, since a waiting item has no row.
        ConversationRunQueue restarted = new ConversationRunQueue(dispatched::add, 5);

        assertThat(restarted.isBusy(CONVERSATION)).isFalse();
        assertThat(restarted.remove(CONVERSATION, itemId)).isFalse();
        assertThat(restarted.submitStreaming(CONVERSATION, new RecordingTurn()))
                .isInstanceOf(Admission.Started.class);
    }

    @Test
    void aTurnThePoolRefusesDoesNotWedgeTheConversation() {
        ConversationRunQueue saturated =
                new ConversationRunQueue(
                        command -> {
                            throw new RejectedExecutionException("pool saturated");
                        },
                        5);

        assertThatThrownBy(() -> saturated.submitStreaming(CONVERSATION, new RecordingTurn()))
                .isInstanceOf(RejectedExecutionException.class);

        // The slot went back: the conversation can be used again instead of looking busy forever.
        assertThat(saturated.isBusy(CONVERSATION)).isFalse();
        assertThat(saturated.runBlocking(CONVERSATION, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void everyConversationHasItsOwnLine() {
        RecordingTurn first = new RecordingTurn();

        queue.submitStreaming(1L, first);

        // Another conversation is not blocked by the first one's turn.
        assertThat(queue.submitStreaming(2L, new RecordingTurn()))
                .isInstanceOf(Admission.Started.class);
        assertThat(first.dropped).isFalse();
        assertThat(dispatched).hasSize(2);
    }

    /**
     * US-8.5：排队项上工用的是"提交它的人"的关联标识。派发它的是上一个回合的收尾线程——那个线程的 MDC
     * 属于刚结束的回合；排队项若不自己带着快照，日志里就会张冠李戴，而这是这条链上唯一会错的地方。
     */
    @Test
    void aQueuedTurnStartsWithTheSubmittersIdsNotTheFinishingThreads() {
        queue.submitStreaming(CONVERSATION, new RecordingTurn());

        MdcContext.put(MdcContext.TRACE_ID, "trace-waiter");
        RecordingTurn waiting = new RecordingTurn();
        queue.submitStreaming(CONVERSATION, waiting);

        // 收尾线程（前一个回合的延续）身上是别的标识；它来派发排队项，不得污染它。
        MdcContext.put(MdcContext.TRACE_ID, "trace-of-previous-turn");
        queue.finished(CONVERSATION);
        runDispatched();

        assertThat(waiting.traceIdAtStart).isEqualTo("trace-waiter");
        // 派发结束，线程还回原样：收尾逻辑后半段自己写的日志还得是自己的标识。
        assertThat(MDC.get(MdcContext.TRACE_ID)).isEqualTo("trace-of-previous-turn");
    }

    private void runDispatched() {
        List<Runnable> pending = new ArrayList<>(dispatched);
        dispatched.clear();
        pending.forEach(Runnable::run);
    }

    private static RecordingTurn recording(List<String> order, String label) {
        return new RecordingTurn() {
            @Override
            public void start() {
                order.add(label);
                super.start();
            }
        };
    }

    /** A turn that remembers what was done to it; {@code recording} narrows it for order checks. */
    private static class RecordingTurn implements ConversationRunQueue.Turn {

        private boolean started;
        private boolean dropped;
        private String traceIdAtStart;

        @Override
        public void start() {
            started = true;
            traceIdAtStart = MDC.get(MdcContext.TRACE_ID);
        }

        @Override
        public void drop() {
            dropped = true;
        }
    }
}
