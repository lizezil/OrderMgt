package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.assistant.application.AiConcurrencyGuard;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.domain.RunInterruptedException;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Component
@Profile("!offline")
@RequiredArgsConstructor
public class SpringAiAdapter implements AiAssistantPort {

    private final ChatClient.Builder builder;
    private final ToolRegistry registry;
    private final GroundingProvider groundingProvider;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private final String model;

    /**
     * The prompt budget in characters, system prompt and grounding included. qwen-max's input
     * window is 30,720 tokens; at ~2 characters per token (deliberately conservative for
     * mostly-Chinese prose) 40,000 chars ≈ 20k tokens, leaving headroom for the output allowance
     * and for the estimate being wrong -- which the context-overflow retry below assumes.
     */
    @Value("${assistant.ai.max-prompt-chars:40000}")
    private final int maxPromptChars;

    /** The global in-flight ceiling; the permit spans one whole call, stream included. */
    private final AiConcurrencyGuard concurrencyGuard;

    @Override
    public AssistantTurn respond(Long customerId, Long conversationId, List<ChatMessage> memory) {
        // One permit per model call, held for its whole life (the overflow replay included): the
        // global concurrency ceiling counts calls, not HTTP round trips.
        return concurrencyGuard.withPermit(
                () -> respondWithinGuard(systemPrompt(customerId, conversationId), memory));
    }

    private AssistantTurn respondWithinGuard(String system, List<ChatMessage> memory) {
        long start = System.nanoTime();
        try {
            ChatClientResponse clientResponse = prompt(system, memory).call().chatClientResponse();
            return toTurn(
                    clientResponse == null ? null : clientResponse.chatResponse(),
                    elapsedMs(start));
        } catch (RuntimeException ex) {
            if (isContextOverflow(ex)) {
                return respondWithHalvedBudget(system, memory, start);
            }
            if (isRateLimited(ex)) {
                throw rateLimited(ex);
            }
            throw upstreamFailure("respond", ex, elapsedMs(start));
        }
    }

    /**
     * The char estimate was too generous: replay the history against half the budget before
     * declaring the conversation unwieldable. One retry only -- a second overflow means even two
     * recent turns will not fit, and the honest answer is to start a new conversation.
     */
    private AssistantTurn respondWithHalvedBudget(
            String system, List<ChatMessage> memory, long start) {
        try {
            ChatClientResponse clientResponse =
                    prompt(system, memory, maxPromptChars / 2).call().chatClientResponse();
            return toTurn(
                    clientResponse == null ? null : clientResponse.chatResponse(),
                    elapsedMs(start));
        } catch (RuntimeException ex) {
            if (isContextOverflow(ex)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对话过长，请开新对话再试");
            }
            if (isRateLimited(ex)) {
                throw rateLimited(ex);
            }
            throw upstreamFailure("respond", ex, elapsedMs(start));
        }
    }

    /** The provider's way of saying the prompt outgrew the model's window. */
    private static boolean isContextOverflow(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if (lower.contains("context_length_exceeded")
                    || lower.contains("context length")
                    || lower.contains("context window")
                    || lower.contains("maximum context")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cause carrying an upstream 429, if any (R1). Three shapes: the WebClient path throws
     * {@link WebClientResponseException} (its own exception family -- not a RestClient subclass --
     * but typed, with status and headers); the blocking path goes through spring.ai.retry's
     * response error handler, which formats its {@code NonTransientAiException} message as {@code
     * "HTTP 429 - <body>"} and drops the headers. That handler classifies 429 as 4xx =>
     * non-transient, so the framework RetryTemplate never replays it either -- this walk only
     * decides what to tell *our* caller.
     */
    private static Throwable rateLimitedCause(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response
                    && response.getStatusCode().value() == 429) {
                return cause;
            }
            if (cause instanceof WebClientResponseException response
                    && response.getStatusCode().value() == 429) {
                return cause;
            }
            String message = cause.getMessage();
            if (message != null && message.regionMatches(true, 0, "HTTP 429", 0, 8)) {
                return cause;
            }
        }
        return null;
    }

    /** Upstream said "slow down": see {@link #rateLimitedCause} for the shapes it arrives in. */
    static boolean isRateLimited(Throwable ex) {
        return rateLimitedCause(ex) != null;
    }

    /**
     * The seconds the upstream named on its Retry-After, else the policy fallback. Only the
     * header-carrying transports can honor the exact value; a date-form header or missing one falls
     * back rather than guessing.
     */
    static int upstreamRetryAfterSeconds(Throwable ex) {
        Throwable cause = rateLimitedCause(ex);
        HttpHeaders headers = null;
        if (cause instanceof RestClientResponseException response) {
            headers = response.getResponseHeaders();
        } else if (cause instanceof WebClientResponseException response) {
            headers = response.getHeaders();
        }
        if (headers != null) {
            String header = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (header != null) {
                try {
                    return Math.max(1, Integer.parseInt(header.trim()));
                } catch (NumberFormatException ignored) {
                    // HTTP-date form or garbage: fall back rather than guess.
                }
            }
        }
        return AiCallPolicy.UPSTREAM_429_FALLBACK_RETRY_SECONDS;
    }

    /** 429 in, RATE_LIMITED out -- with the upstream's wait passed through where it exists. */
    private static BusinessException rateLimited(Throwable ex) {
        int retryAfter = upstreamRetryAfterSeconds(ex);
        return new BusinessException(
                ErrorCode.RATE_LIMITED, "AI 服务上游限流，请等 " + retryAfter + " 秒再试", retryAfter);
    }

    @Override
    public AssistantTurn respondStreaming(
            Long customerId,
            Long conversationId,
            List<ChatMessage> memory,
            Consumer<String> onTextDelta,
            RunCancellation cancellation) {
        // The permit spans the whole stream -- before the first token through to the last --
        // because
        // that is how long the model connection is actually occupied. A guard denial here surfaces
        // to the client as the stream's `error` frame; the admission-time JSON 429 is the
        // per-customer limiter's job (see ConversationController#stream).
        return concurrencyGuard.withPermit(
                () ->
                        streamWithinGuard(
                                systemPrompt(customerId, conversationId),
                                memory,
                                onTextDelta,
                                cancellation));
    }

    private AssistantTurn streamWithinGuard(
            String system,
            List<ChatMessage> memory,
            Consumer<String> onTextDelta,
            RunCancellation cancellation) {
        long start = System.nanoTime();

        // A replay is only safe while the client has seen nothing: retrying after the first
        // token would put duplicated text on the wire, so from then on the stream either
        // finishes or fails honestly.
        AtomicBoolean emitted = new AtomicBoolean(false);
        Consumer<String> tracked =
                delta -> {
                    emitted.set(true);
                    onTextDelta.accept(delta);
                };

        // An OpenAI-style stream hands the prose over as deltas and closes on a textless finish
        // chunk, so the turn is accumulated while the bytes flow -- never read off the final
        // chunk alone, which would bill the reply (and its history) as nothing.
        StreamedTurn streamed = new StreamedTurn();
        AssistantTurn turn;
        int budget = maxPromptChars;
        boolean overflowRetried = false;
        while (true) {
            try {
                int passBudget = budget;
                turn =
                        retryBeforeFirstToken(
                                () -> {
                                    streamed.reset();
                                    streamOnce(
                                            system,
                                            memory,
                                            passBudget,
                                            tracked,
                                            streamed,
                                            cancellation);
                                    return streamed.toTurn(model, elapsedMs(start));
                                },
                                emitted::get,
                                AiCallPolicy.STREAM_RETRY_ATTEMPTS,
                                SpringAiAdapter::sleepForRetry);
                break;
            } catch (RuntimeException ex) {
                if (!emitted.get() && !overflowRetried && isContextOverflow(ex)) {
                    // Before any token is out this is still a prompt problem, not a wire problem:
                    // halve the budget and replay once rather than surfacing the provider's 400.
                    overflowRetried = true;
                    budget = budget / 2;
                    continue;
                }
                // Tokens already on the wire were already paid for and already seen: they leave
                // with the exception as a partial turn, never discarded with the stack trace.
                if (emitted.get()) {
                    throw new RunInterruptedException(streamed.toTurn(model, elapsedMs(start)), ex);
                }
                if (overflowRetried && isContextOverflow(ex)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对话过长，请开新对话再试");
                }
                // Upstream "slow down", caught before any token: a replay would only ask for the
                // same refusal harder, so surface the 429 with the wait the upstream named.
                if (isRateLimited(ex)) {
                    throw rateLimited(ex);
                }
                throw upstreamFailure("respondStreaming", ex, elapsedMs(start));
            }
        }
        if (cancellation.isCancelled()) {
            // The stop aborted the upstream subscription; the prose received so far is the turn,
            // and the caller persists it as an ordinary ASSISTANT message (ADR-0012). A cancel
            // arriving with no delta yet carries an empty turn -- still CANCELLED, still no error.
            throw new RunCancelledException(turn);
        }
        return turn;
    }

    /**
     * What a stream accumulates while it runs: prose deltas in order, the newest non-empty tool
     * calls, and the usage of the last chunk that carried any. Each fact lives on the chunk that
     * carries it -- the finish chunk carries none of them.
     */
    private final class StreamedTurn {

        private final StringBuilder prose = new StringBuilder();
        private List<ProposedToolCall> toolCalls = List.of();
        private AiUsage usage = AiUsage.EMPTY;

        /** Adds one chunk to the turn and hands back its prose delta, for the wire. */
        String accept(ChatResponse chunk) {
            String delta = textOf(chunk);
            prose.append(delta);
            if (chunk.hasToolCalls()) {
                toolCalls = toolCallsOf(chunk);
            }
            AiUsage chunkUsage = usageOf(chunk);
            if (!AiUsage.EMPTY.equals(chunkUsage)) {
                usage = chunkUsage;
            }
            return delta;
        }

        void reset() {
            prose.setLength(0);
            toolCalls = List.of();
            usage = AiUsage.EMPTY;
        }

        AssistantTurn toTurn(String model, int latencyMs) {
            return new AssistantTurn(prose.toString(), toolCalls, usage, model, latencyMs);
        }
    }

    /** One pass over the model stream; anything before the first delta is retryable. */
    private void streamOnce(
            String system,
            List<ChatMessage> memory,
            int budgetChars,
            Consumer<String> onTextDelta,
            StreamedTurn streamed,
            RunCancellation cancellation) {
        prompt(system, memory, budgetChars).stream()
                .chatClientResponse()
                // A stop trips this predicate at the next chunk, which completes the flux and
                // cancels the upstream subscription -- the HTTP request to the model is torn
                // down, so the tokens nobody will read are not paid for (ADR-0012).
                .takeWhile(response -> !cancellation.isCancelled())
                .doOnNext(
                        response -> {
                            ChatResponse chunk = response.chatResponse();
                            if (chunk == null) {
                                return;
                            }
                            String delta = streamed.accept(chunk);
                            if (!delta.isEmpty()) {
                                onTextDelta.accept(delta);
                            }
                        })
                // Belt and braces: `.timeout` errors out on a stalled stream (no delta for two
                // minutes), `.blockLast` bounds the total wall clock even if the upstream keeps
                // trickling tokens. The framework never retries a stream -- the whole-stream
                // replay before the first token is ours (see retryBeforeFirstToken).
                .timeout(AiCallPolicy.STREAM_TIMEOUT)
                .blockLast(AiCallPolicy.STREAM_TIMEOUT);
    }

    /**
     * First-token retry: a stream that dies before the client has seen anything is replayed whole;
     * once a token is out, failure is final. Backoff mirrors spring.ai.retry (1s, ×2, capped at
     * 8s), so the blocking and streaming transports wait the same way.
     *
     * <p>Package-private and model-free -- the attempt arrives as a lambda -- so every branch of
     * the policy can be tested without a model.
     */
    static AssistantTurn retryBeforeFirstToken(
            Supplier<AssistantTurn> attempt,
            BooleanSupplier emitted,
            int maxAttempts,
            LongConsumer backoff) {
        RuntimeException failure = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            try {
                return attempt.get();
            } catch (RuntimeException ex) {
                failure = ex;
                if (isRateLimited(ex)) {
                    // R1: a rate limit is not a wobble -- a replay waits out nothing and asks for
                    // the same refusal harder. The caller translates this into 429 + Retry-After.
                    log.warn(
                            "upstream refused the stream with 429; not replaying (attempt {}/{})",
                            attemptNo,
                            maxAttempts);
                    break;
                }
                if (emitted.getAsBoolean() || attemptNo == maxAttempts) {
                    break;
                }
                long delayMs = backoffMs(attemptNo);
                log.warn(
                        "stream attempt {}/{} failed before any token, retrying in {}ms: {}",
                        attemptNo,
                        maxAttempts,
                        delayMs,
                        ex.getMessage());
                backoff.accept(delayMs);
            }
        }
        throw failure;
    }

    /** 1s, 2s, 4s, ... clamped at the 8s cap -- same schedule spring.ai.retry uses. */
    private static long backoffMs(int failedAttempt) {
        return Math.min(
                AiCallPolicy.RETRY_INITIAL_BACKOFF.toMillis() * (1L << (failedAttempt - 1)),
                AiCallPolicy.RETRY_MAX_BACKOFF.toMillis());
    }

    private static void sleepForRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry the stream", ex);
        }
    }

    /**
     * The model call is the one dependency here that can fail for reasons that are not this
     * service's fault. Translating it keeps a lapsed key or a provider outage from arriving as a
     * generic 500, and carrying the audit facts (model, latency, outcome) lets the caller still
     * record the failed call.
     */
    private RuntimeException upstreamFailure(String operation, RuntimeException ex, int latencyMs) {
        if (ex instanceof BusinessException businessException) {
            return businessException;
        }
        log.warn(
                "upstream AI call failed during {} after {}ms: {}",
                operation,
                latencyMs,
                ex.getMessage());
        return new AiUpstreamException(
                ErrorCode.UPSTREAM_AI_ERROR,
                ErrorCode.UPSTREAM_AI_ERROR.defaultMessage(),
                ex,
                model,
                latencyMs,
                outcomeOf(ex));
    }

    /**
     * A timeout anywhere in the chain is a timeout; anything else is a plain upstream error.
     *
     * <p>Three shapes count, because the two transports report a timeout differently: Reactor's
     * {@code .timeout()} raises {@link TimeoutException}, while the JDK HttpClient behind {@code
     * JdkClientHttpRequestFactory} reports an expired read/request deadline as {@link
     * SocketTimeoutException} or {@link HttpTimeoutException} -- IOExceptions that would otherwise
     * land in the audit as a generic ERROR. Package-private so the classification can be tested
     * directly against each real shape.
     */
    static AiInvocationOutcome outcomeOf(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return AiInvocationOutcome.TIMEOUT;
            }
        }
        return AiInvocationOutcome.ERROR;
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    private ChatClient.ChatClientRequestSpec prompt(String system, List<ChatMessage> memory) {
        return prompt(system, memory, maxPromptChars);
    }

    private ChatClient.ChatClientRequestSpec prompt(
            String system, List<ChatMessage> memory, int budgetChars) {
        ToolCallback[] callbacks =
                registry.all().stream().map(RegistryToolCallback::new).toArray(ToolCallback[]::new);

        // The approval gate: no tool may run inside the model call.
        ChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(callbacks)
                        .internalToolExecutionEnabled(false)
                        .build();

        // Built once per call by the caller: grounding is regenerated per request (never a stale
        // snapshot) and must not be paid for twice just because the budget also needs its length.
        List<ChatMessage> fitted = fitWithinBudget(memory, budgetChars - system.length());

        return chatClient()
                .prompt()
                .system(system)
                .messages(toSpringMessages(fitted))
                .options(options);
    }

    /**
     * The newest turns that stay no matter what the budget says: a conversation needs its own tail.
     */
    private static final int KEEP_AT_LEAST = 2;

    /** Rough per-message wrapper cost (role framing) so the estimate is not optimistic. */
    private static final int ROLE_OVERHEAD = 8;

    /**
     * Drops the oldest turns until the history fits what remains of the budget after the system
     * prompt -- characters, not a message count, because 20 turns of 8 000 characters each is ~40k
     * tokens on a 30k-token window. The last {@link #KEEP_AT_LEAST} turns are exempt: even an
     * over-long conversation keeps the exchange the model is answering into.
     */
    private static List<ChatMessage> fitWithinBudget(List<ChatMessage> memory, int historyBudget) {
        if (memory.size() <= KEEP_AT_LEAST) {
            return memory;
        }
        int used = 0;
        java.util.LinkedList<ChatMessage> kept = new java.util.LinkedList<>();
        for (int i = memory.size() - 1; i >= 0; i--) {
            ChatMessage turn = memory.get(i);
            int length = (turn.content() == null ? 0 : turn.content().length()) + ROLE_OVERHEAD;
            if (kept.size() >= KEEP_AT_LEAST && used + length > historyBudget) {
                break;
            }
            used += length;
            kept.addFirst(turn);
        }
        return kept;
    }

    /**
     * Building per call keeps the constructor Lombok-generated. {@code ChatClient.Builder} is a
     * cheap configuration holder -- build() allocates a client over the same model, no I/O.
     */
    private ChatClient chatClient() {
        return builder.build();
    }

    private AssistantTurn toTurn(ChatResponse chatResponse, int latencyMs) {
        if (chatResponse == null) {
            return new AssistantTurn("", List.of(), AiUsage.EMPTY, model, latencyMs);
        }
        return new AssistantTurn(
                textOf(chatResponse),
                toolCallsOf(chatResponse),
                usageOf(chatResponse),
                model,
                latencyMs);
    }

    private List<ProposedToolCall> toolCallsOf(ChatResponse chatResponse) {
        List<ProposedToolCall> calls = new ArrayList<>();
        if (chatResponse.hasToolCalls()) {
            for (AssistantMessage.ToolCall call :
                    chatResponse.getResult().getOutput().getToolCalls()) {
                boolean mutating = registry.find(call.name()).map(ToolSpec::mutating).orElse(true);
                if (registry.find(call.name()).isEmpty()) {
                    log.warn("model asked for an unregistered tool: {}", call.name());
                }
                calls.add(new ProposedToolCall(call.name(), call.arguments(), mutating));
            }
        }
        return calls;
    }

    private static String textOf(ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse.getResult())
                .map(r -> r.getOutput())
                .map(AssistantMessage::getText)
                .orElse("");
    }

    private static AiUsage usageOf(ChatResponse chatResponse) {
        Usage usage =
                chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
        return usage == null
                ? AiUsage.EMPTY
                : new AiUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /**
     * Grounding is built per request so the model always sees the current catalog and this
     * conversation's latest confirmed order, not a snapshot baked in at startup. It is built once
     * per model call and then carried down as a string: the same facts must describe the whole
     * call, the budget/overflow replay included.
     */
    private String systemPrompt(Long customerId, Long conversationId) {
        return AiPrompts.SYSTEM + "\n" + groundingProvider.facts(customerId, conversationId);
    }

    private List<Message> toSpringMessages(List<ChatMessage> memory) {
        List<Message> messages = new ArrayList<>(memory.size());
        for (ChatMessage turn : memory) {
            messages.add(
                    switch (turn.role()) {
                        case CUSTOMER -> new UserMessage(nullToEmpty(turn.content()));
                        case ASSISTANT -> new AssistantMessage(nullToEmpty(turn.content()));
                        case TOOL ->
                                new UserMessage("[Tool result] " + nullToEmpty(turn.content()));
                    });
        }
        return messages;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
