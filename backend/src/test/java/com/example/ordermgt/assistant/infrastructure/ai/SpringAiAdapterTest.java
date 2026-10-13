package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * The audit outcome is only as good as the exception classification: a real read timeout that is
 * recorded as ERROR hides the one failure mode operators care about most. These are the exception
 * shapes the two transports actually produce, not idealised ones.
 */
class SpringAiAdapterTest {

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
}
