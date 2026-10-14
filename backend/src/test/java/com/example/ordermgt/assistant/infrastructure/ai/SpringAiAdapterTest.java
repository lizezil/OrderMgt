package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.assistant.application.AiConcurrencyGuard;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.domain.RunInterruptedException;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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

    /**
     * Grounding only works if the facts actually reach the model: building them and then dropping
     * them on the floor would leave the prompt exactly as ungrounded as before. So the system
     * prompt must carry what the provider served -- every kind of fact it serves, and served for
     * the right conversation: 「刚刚那个订单」 is a fact about one conversation, not about a customer's
     * whole history (ADR-0015).
     */
    @Test
    void theSystemPromptCarriesTheGroundingFacts() {
        List<String> scopes = new ArrayList<>();
        GroundingProvider grounding =
                (customerId, conversationId) -> {
                    scopes.add(customerId + "/" + conversationId);
                    return "Available items (take skus exactly from here):\n"
                            + "- WIDGET-01 unit price 19.90\n"
                            + AiPrompts.LAST_CONFIRMED_ORDER_HEADER
                            + "\n- ORD2026101421214197C492 (Place order: GIZMO-03 × 1)";
                };
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse()).thenReturn(chunk("hello"));

        new SpringAiAdapter(
                        builder,
                        new ToolRegistry(List.of()),
                        grounding,
                        "test-model",
                        40_000,
                        new AiConcurrencyGuard(64))
                .respond(1001L, 7L, List.of());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemPrompt.capture());
        assertThat(scopes).containsExactly("1001/7");
        assertThat(systemPrompt.getValue())
                .contains("WIDGET-01 unit price 19.90")
                .contains("ORD2026101421214197C492");
    }

    /** 并发名额为零：调用在碰模型之前就被 429 拒绝，builder 连 build 都不该被调到。 */
    @Test
    void aSaturatedGuardRefusesTheCallBeforeTheModelIsTouched() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        SpringAiAdapter adapter =
                new SpringAiAdapter(
                        builder,
                        new ToolRegistry(List.of()),
                        (customerId, conversationId) -> "",
                        "test-model",
                        40_000,
                        new AiConcurrencyGuard(0));

        assertThatThrownBy(() -> adapter.respond(1001L, 7L, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).errorCode())
                                        .isEqualTo(ErrorCode.RATE_LIMITED));
        verify(builder, never()).build();
    }

    /**
     * R1：上游 429 绝不自动重试，且必须以 429 + Retry-After 透传——不是 502 通用上游错误。 阻塞路径的 429 被 spring.ai.retry 分类为
     * NonTransientAiException（4xx 规则），框架只发起一次调用。
     */
    @Test
    void anUpstream429OnTheBlockingPathSurfacesAsRateLimitedWithoutAReplay() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatClientResponse())
                .thenThrow(new NonTransientAiException("HTTP 429 - {\"error\":\"rate limited\"}"));
        SpringAiAdapter adapter =
                new SpringAiAdapter(
                        builder,
                        new ToolRegistry(List.of()),
                        (customerId, conversationId) -> "",
                        "test-model",
                        40_000,
                        new AiConcurrencyGuard(64));

        assertThatThrownBy(() -> adapter.respond(1001L, 7L, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException business = (BusinessException) ex;
                            assertThat(business.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            // 阻塞传输拿不到响应头，用兜底秒数而不是假装知道上游的值。
                            assertThat(business.retryAfterSeconds())
                                    .isEqualTo(AiCallPolicy.UPSTREAM_429_FALLBACK_RETRY_SECONDS);
                        });
        verify(call, times(1)).chatClientResponse();
    }

    /** R1（流式）：首 token 前撞上上游 429——不重放，Retry-After 按上游的值透传。 */
    @Test
    void aStreamRefusedWith429IsNotReplayedAndCarriesTheUpstreamRetryAfter() {
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
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Retry-After", "7");
        when(stream.chatClientResponse())
                .thenReturn(
                        Flux.error(
                                WebClientResponseException.create(
                                        429, "Too Many Requests", headers, null, null)));
        SpringAiAdapter adapter =
                new SpringAiAdapter(
                        builder,
                        new ToolRegistry(List.of()),
                        (customerId, conversationId) -> "",
                        "test-model",
                        40_000,
                        new AiConcurrencyGuard(64));

        assertThatThrownBy(
                        () ->
                                adapter.respondStreaming(
                                        1001L, 7L, List.of(), delta -> {}, () -> false))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException business = (BusinessException) ex;
                            assertThat(business.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(business.retryAfterSeconds()).isEqualTo(7);
                        });
        // 一次都没有重放：429 打过来再打一次还是 429，重放只是火上浇油。
        verify(stream, times(1)).chatClientResponse();
    }

    /** 重放闸门本身：429 形状的失败第一次就停，退避一次都不走。 */
    @Test
    void retryBeforeFirstTokenNeverReplaysARateLimitedFailure() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        RuntimeException rateLimited =
                WebClientResponseException.create(
                        429,
                        "Too Many Requests",
                        new org.springframework.http.HttpHeaders(),
                        null,
                        null);

        assertThatThrownBy(
                        () ->
                                SpringAiAdapter.retryBeforeFirstToken(
                                        () -> {
                                            attempts.incrementAndGet();
                                            throw rateLimited;
                                        },
                                        () -> false,
                                        3,
                                        delays::add))
                .isSameAs(rateLimited);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(delays).isEmpty();
    }

    /**
     * 钉住对 spring.ai.retry 分类的信任：429 是 4xx → NonTransientAiException → 模板只打一次；
     * 真正的瞬时错误（TransientAiException）仍然重试满 3 次——R1 禁的只是 429。
     */
    @Test
    void theFrameworkRetryTemplateNeverReplaysAnHttp429ButStillRetriesTransientFailures() {
        SpringAiRetryProperties properties = new SpringAiRetryProperties();
        properties.getBackoff().setInitialInterval(Duration.ofMillis(1));
        properties.getBackoff().setMaxInterval(Duration.ofMillis(2));
        RetryTemplate template = new SpringAiRetryAutoConfiguration().retryTemplate(properties);

        AtomicInteger rateLimitedAttempts = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                template.execute(
                                        ctx -> {
                                            rateLimitedAttempts.incrementAndGet();
                                            throw new NonTransientAiException(
                                                    "HTTP 429 - rate limited");
                                        }))
                .isInstanceOf(NonTransientAiException.class);
        assertThat(rateLimitedAttempts.get()).isEqualTo(1);

        AtomicInteger transientAttempts = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                template.execute(
                                        ctx -> {
                                            transientAttempts.incrementAndGet();
                                            throw new TransientAiException("connection reset");
                                        }))
                .isInstanceOf(TransientAiException.class);
        assertThat(transientAttempts.get()).isEqualTo(properties.getMaxAttempts());
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

        AssistantTurn turn =
                adapter.respondStreaming(1001L, 7L, List.of(), delta -> {}, RunCancellation.NEVER);

        assertThat(turn.text()).isEqualTo("你有 3 张订单。");
    }

    /** Token spend arrives on whichever chunk carries usage; dropping it bills the call as free. */
    @Test
    void streamedTurnKeepsUsageWhenAnyChunkCarriesIt() {
        SpringAiAdapter adapter =
                adapterOver(chunk("hello"), chunkWithUsage(" there", 12, 34), chunk(""));

        AssistantTurn turn =
                adapter.respondStreaming(1001L, 7L, List.of(), delta -> {}, RunCancellation.NEVER);

        assertThat(turn.usage().promptTokens()).isEqualTo(12);
        assertThat(turn.usage().completionTokens()).isEqualTo(34);
    }

    /** Tool calls ride in the final content chunk; a textless finish chunk must not erase them. */
    @Test
    void streamedToolCallsSurviveATextlessFinishChunk() {
        SpringAiAdapter adapter = adapterOver(chunkWithToolCall(), chunk(""));

        AssistantTurn turn =
                adapter.respondStreaming(1001L, 7L, List.of(), delta -> {}, RunCancellation.NEVER);

        assertThat(turn.toolCalls())
                .extracting(ProposedToolCall::name)
                .containsExactly("queryMyOrders");
    }

    /**
     * 停止真的掐断上游（ADR-0012）：取消信号一翻，下一个 chunk 就被 {@code takeWhile} 拦下、上游订阅被取消， 已经吐出的散文作为半截 turn 随
     * {@link RunCancelledException} 带出，交给上层落库。中止是"到下一个 chunk 为止"，但那个 chunk 之后再没有一个 token 会被读取或计费。
     */
    @Test
    void aCancelledStreamAbortsAtTheNextChunkAndCarriesTheProseSoFar() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        SpringAiAdapter adapter = adapterOver(chunk("你"), chunk("好"), chunk("世界"));

        // The stop arrives while the first delta is being delivered, exactly where a real one
        // would.
        RunCancelledException stopped =
                catchThrowableOfType(
                        () ->
                                adapter.respondStreaming(
                                        1001L,
                                        7L,
                                        List.of(),
                                        delta -> cancelled.set(true),
                                        cancelled::get),
                        RunCancelledException.class);

        assertThat(stopped).isNotNull();
        assertThat(stopped.partial().text()).isEqualTo("你");
    }

    /**
     * 上游在吐字之后断掉：客户已经看过的半截文本不能跟着异常一起被丢弃——它必须作为半截 turn 随 {@link RunInterruptedException}
     * 带出，由上层落库并标注[生成中断]。
     */
    @Test
    void aStreamThatDiesMidReplyCarriesTheProseSoFarAsAnInterruption() {
        SpringAiAdapter adapter =
                adapterOver(
                        Flux.concat(
                                Flux.just(chunk("你有 3 ")),
                                Flux.error(new IllegalStateException("upstream went away"))));

        RunInterruptedException broken =
                catchThrowableOfType(
                        () ->
                                adapter.respondStreaming(
                                        1001L, 7L, List.of(), delta -> {}, RunCancellation.NEVER),
                        RunInterruptedException.class);

        assertThat(broken).isNotNull();
        assertThat(broken.partial().text()).isEqualTo("你有 3 ");
    }

    /** 记忆窗口按条数（20）不按大小：每条可达 8000 字符，20 条足以把 32k 上下文顶爆。预算裁剪从最旧 丢起，模型最近看过的话优先保留。 */
    @Test
    void historyIsTrimmedOldestFirstWhenThePromptWouldExceedTheBudget() {
        Budgeted stub = budgetedOver(12_000, chunk("ok"));
        List<ChatMessage> memory = turns(10, 5000);

        stub.adapter().respond(1001L, 7L, memory);

        ArgumentCaptor<List<Message>> sent = ArgumentCaptor.forClass(List.class);
        verify(stub.spec()).messages(sent.capture());
        assertThat(sent.getValue()).hasSize(2);
        assertThat(sent.getValue().get(1).getText()).isEqualTo(memory.get(9).content());
        assertThat(sent.getValue().get(0).getText()).isEqualTo(memory.get(8).content());
    }

    /** 保底是对话的底线：预算再紧，最近两条也必须在场，否则模型连上一句说过什么都看不见。 */
    @Test
    void theLastTwoTurnsSurviveEvenABudgetThatFitsNone() {
        Budgeted stub = budgetedOver(100, chunk("ok"));

        stub.adapter().respond(1001L, 7L, turns(5, 5000));

        ArgumentCaptor<List<Message>> sent = ArgumentCaptor.forClass(List.class);
        verify(stub.spec()).messages(sent.capture());
        assertThat(sent.getValue()).hasSize(2);
    }

    /** 裁剪后仍超限（粗估低估了）：预算砍半再试一次，而不是把 400 原样抛给客户。 */
    @Test
    void aContextOverflowRetriesOnceWithHalfTheBudgetBeforeGivingUp() {
        AtomicInteger attempts = new AtomicInteger();
        Budgeted stub = budgetedOver(40_000, chunk("ok"));
        when(stub.call().chatClientResponse())
                .thenAnswer(
                        inv -> {
                            if (attempts.getAndIncrement() == 0) {
                                throw new IllegalStateException(
                                        "Error: context_length_exceeded: the conversation is too long");
                            }
                            return chunk("ok");
                        });

        AssistantTurn turn = stub.adapter().respond(1001L, 7L, turns(3, 5000));

        assertThat(turn.text()).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    /** 砍半也救不了：这是客户能行动的失败——开新对话，而不是一个含糊的 502。 */
    @Test
    void anOverflowThatSurvivesTheSmallerBudgetIsToldToStartANewConversation() {
        Budgeted stub = budgetedOver(40_000, chunk("ok"));
        when(stub.call().chatClientResponse())
                .thenThrow(
                        new IllegalStateException(
                                "Error: context_length_exceeded: the conversation is too long"));

        assertThatThrownBy(() -> stub.adapter().respond(1001L, 7L, turns(3, 5000)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            assertThat(((BusinessException) ex).errorCode())
                                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
                            assertThat(ex).hasMessageContaining("对话过长");
                        });
    }

    /** n turns of roughly {@code chars} characters, alternating speakers, oldest first. */
    private static List<ChatMessage> turns(int count, int chars) {
        List<ChatMessage> memory = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            memory.add(
                    new ChatMessage(
                            i % 2 == 0 ? Role.CUSTOMER : Role.ASSISTANT,
                            "第" + i + "条 " + "x".repeat(chars)));
        }
        return memory;
    }

    private record Budgeted(
            ChatClient.ChatClientRequestSpec spec,
            ChatClient.CallResponseSpec call,
            SpringAiAdapter adapter) {}

    /**
     * The stream chain plus the blocking call chain, over an adapter with a custom prompt budget.
     */
    private static Budgeted budgetedOver(int maxPromptChars, ChatClientResponse... chunks) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(spec.stream()).thenReturn(stream);
        when(call.chatClientResponse()).thenReturn(chunk("ok"));
        when(stream.chatClientResponse()).thenReturn(Flux.fromArray(chunks));
        SpringAiAdapter adapter =
                new SpringAiAdapter(
                        builder,
                        new ToolRegistry(List.of()),
                        (customerId, conversationId) -> "",
                        "test-model",
                        maxPromptChars,
                        new AiConcurrencyGuard(64));
        return new Budgeted(spec, call, adapter);
    }

    /** The whole ChatClient stream chain, stubbed at the seams {@code respondStreaming} touches. */
    private static SpringAiAdapter adapterOver(ChatClientResponse... chunks) {
        return adapterOver(Flux.fromArray(chunks));
    }

    private static SpringAiAdapter adapterOver(Flux<ChatClientResponse> chunks) {
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
        when(stream.chatClientResponse()).thenReturn(chunks);
        return new SpringAiAdapter(
                builder,
                new ToolRegistry(List.of()),
                (customerId, conversationId) -> "",
                "test-model",
                40_000,
                new AiConcurrencyGuard(64));
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
