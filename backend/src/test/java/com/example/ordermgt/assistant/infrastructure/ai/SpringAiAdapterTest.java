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
    void JDK客户端的读超时记成TIMEOUT而不是ERROR() {
        assertThat(SpringAiAdapter.outcomeOf(new SocketTimeoutException("Read timed out")))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void Spring把读超时包进ResourceAccessException后仍记成TIMEOUT() {
        IOException readTimeout = new SocketTimeoutException("connect timed out");
        assertThat(
                        SpringAiAdapter.outcomeOf(
                                new ResourceAccessException("POST /chat", readTimeout)))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void 请求头超时的HttpTimeoutException也记成TIMEOUT() {
        assertThat(SpringAiAdapter.outcomeOf(new HttpTimeoutException("request timed out")))
                .isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void 流式的Reactor超时包在IllegalStateException里仍记成TIMEOUT() {
        RuntimeException wrapped = new IllegalStateException(new TimeoutException("Flux timeout"));
        assertThat(SpringAiAdapter.outcomeOf(wrapped)).isEqualTo(AiInvocationOutcome.TIMEOUT);
    }

    @Test
    void 非超时的上游故障记成ERROR() {
        assertThat(SpringAiAdapter.outcomeOf(new RuntimeException("connection refused")))
                .isEqualTo(AiInvocationOutcome.ERROR);
    }
}
