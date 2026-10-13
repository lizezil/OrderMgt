package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

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

    /**
     * The stream hands over deltas and closes on a textless finish chunk. The turn -- and therefore
     * the reply the SPA swaps in for its placeholder, and the row history serves on the next visit
     * -- must carry every word the customer watched stream by, not the final chunk's nothing.
     */
    @Test
    void streamedTurnKeepsTheProseFromEveryChunkNotJustTheLast() {
        SpringAiAdapter adapter = adapterOver(chunk("你有 "), chunk("3 张订单。"), chunk(""));

        AssistantTurn turn = adapter.respondStreaming(1001L, List.of(), delta -> {});

        assertThat(turn.text()).isEqualTo("你有 3 张订单。");
    }

    /** Token spend arrives on whichever chunk carries usage; dropping it bills the call as free. */
    @Test
    void streamedTurnKeepsUsageWhenAnyChunkCarriesIt() {
        SpringAiAdapter adapter =
                adapterOver(chunk("hello"), chunkWithUsage(" there", 12, 34), chunk(""));

        AssistantTurn turn = adapter.respondStreaming(1001L, List.of(), delta -> {});

        assertThat(turn.usage().promptTokens()).isEqualTo(12);
        assertThat(turn.usage().completionTokens()).isEqualTo(34);
    }

    /** Tool calls ride in the final content chunk; a textless finish chunk must not erase them. */
    @Test
    void streamedToolCallsSurviveATextlessFinishChunk() {
        SpringAiAdapter adapter = adapterOver(chunkWithToolCall(), chunk(""));

        AssistantTurn turn = adapter.respondStreaming(1001L, List.of(), delta -> {});

        assertThat(turn.toolCalls())
                .extracting(ProposedToolCall::name)
                .containsExactly("queryMyOrders");
    }

    /** The whole ChatClient stream chain, stubbed at the seams {@code respondStreaming} touches. */
    private static SpringAiAdapter adapterOver(ChatClientResponse... chunks) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(Flux.fromArray(chunks));
        return new SpringAiAdapter(builder, new ToolRegistry(List.of()), () -> "", "test-model");
    }

    private static ChatClientResponse chunk(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }

    private static ChatClientResponse chunkWithUsage(
            String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder()
                        .usage(new FixedUsage(promptTokens, completionTokens))
                        .build();
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata),
                Map.of());
    }

    private static ChatClientResponse chunkWithToolCall() {
        AssistantMessage message =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call_1", "function", "queryMyOrders", "{}")))
                        .build();
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(message))), Map.of());
    }

    private static final class FixedUsage implements Usage {
        private final Integer promptTokens;
        private final Integer completionTokens;

        FixedUsage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
