package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * The audit outcome is only as good as the exception classification: a real read timeout that is
 * recorded as ERROR hides the one failure mode operators care about most. These are the exception
 * shapes the two transports actually produce, not idealised ones.
 *
 * <p>Also pins the first-token retry policy: replay while the client has seen nothing, never after,
 * with the spring.ai.retry backoff schedule.
 */
class SpringAiAdapterTest {

    private static final AssistantTurn TURN =
            new AssistantTurn("hello", List.of(), AiUsage.EMPTY, "qwen-max", 42);

    @Test
    void jdkClientReadTimeoutIsRecordedAsTimeoutNotError() {
        assertThat(SpringAiAdapter.outcomeOf(new SocketTimeoutException("Read timed out")))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void readTimeoutWrappedInResourceAccessExceptionStillRecordedAsTimeout() {
        IOException readTimeout = new SocketTimeoutException("connect timed out");
        assertThat(
                        SpringAiAdapter.outcomeOf(
                                new ResourceAccessException("POST /chat", readTimeout)))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void headerTimeoutHttpTimeoutExceptionIsAlsoRecordedAsTimeout() {
        assertThat(SpringAiAdapter.outcomeOf(new HttpTimeoutException("request timed out")))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void reactorStreamingTimeoutWrappedInIllegalStateExceptionStillRecordedAsTimeout() {
        RuntimeException wrapped = new IllegalStateException(new TimeoutException("Flux timeout"));
        assertThat(SpringAiAdapter.outcomeOf(wrapped)).isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void nonTimeoutUpstreamFailureIsRecordedAsError() {
        assertThat(SpringAiAdapter.outcomeOf(new RuntimeException("connection refused")))
                .isEqualTo(AiInvocationOutcome.ERROR);
    }

    @Test
    void aStreamThatDiesBeforeTheFirstTokenIsReplayedWithBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();

        AssistantTurn turn =
                SpringAiAdapter.retryBeforeFirstToken(
                        () -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new IllegalStateException("connection reset");
                            }
                            return TURN;
                        },
                        () -> false,
                        3,
                        delays::add);

        assertThat(turn).isSameAs(TURN);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(delays).containsExactly(1000L, 2000L);
    }

    @Test
    void onceATokenIsOutTheStreamFailsInsteadOfReplaying() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                SpringAiAdapter.retryBeforeFirstToken(
                                        () -> {
                                            attempts.incrementAndGet();
                                            throw new IllegalStateException("mid-stream broke");
                                        },
                                        () -> true,
                                        3,
                                        delays::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mid-stream broke");

        // Replaying now would duplicate text the client already rendered.
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void exhaustedAttemptsSurfaceTheLastFailureHonestly() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                SpringAiAdapter.retryBeforeFirstToken(
                                        () -> {
                                            throw new IllegalStateException(
                                                    "boom " + attempts.incrementAndGet());
                                        },
                                        () -> false,
                                        3,
                                        delays::add))
                .hasMessage("boom 3");

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(delays).containsExactly(1000L, 2000L);
    }

    @Test
    void backoffDoublesAndClampsAtTheEightSecondCap() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                SpringAiAdapter.retryBeforeFirstToken(
                                        () -> {
                                            attempts.incrementAndGet();
                                            throw new IllegalStateException("still down");
                                        },
                                        () -> false,
                                        6,
                                        delays::add))
                .hasMessage("still down");

        assertThat(delays).containsExactly(1000L, 2000L, 4000L, 8000L, 8000L);
    }

    @Test
    void aStreamThatSucceedsOnTheFirstTryNeverWaits() {
        List<Long> delays = new ArrayList<>();

        AssistantTurn turn =
                SpringAiAdapter.retryBeforeFirstToken(() -> TURN, () -> false, 3, delays::add);

        assertThat(turn).isSameAs(TURN);
        assertThat(delays).isEmpty();
    }
}
