package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
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
import org.springframework.stereotype.Component;

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

    @Override
    public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
        long start = System.nanoTime();
        try {
            ChatClientResponse clientResponse = prompt(memory).call().chatClientResponse();
            return toTurn(
                    clientResponse == null ? null : clientResponse.chatResponse(),
                    elapsedMs(start));
        } catch (RuntimeException ex) {
            throw upstreamFailure("respond", ex, elapsedMs(start));
        }
    }

    @Override
    public AssistantTurn respondStreaming(
            Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
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
        try {
            return retryBeforeFirstToken(
                    () -> {
                        streamed.reset();
                        streamOnce(memory, tracked, streamed);
                        return streamed.toTurn(model, elapsedMs(start));
                    },
                    emitted::get,
                    AiCallPolicy.STREAM_RETRY_ATTEMPTS,
                    SpringAiAdapter::sleepForRetry);
        } catch (RuntimeException ex) {
            throw upstreamFailure("respondStreaming", ex, elapsedMs(start));
        }
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
            List<ChatMessage> memory, Consumer<String> onTextDelta, StreamedTurn streamed) {
        prompt(memory).stream()
                .chatClientResponse()
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

    private ChatClient.ChatClientRequestSpec prompt(List<ChatMessage> memory) {
        ToolCallback[] callbacks =
                registry.all().stream().map(RegistryToolCallback::new).toArray(ToolCallback[]::new);

        // The approval gate: no tool may run inside the model call.
        ChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(callbacks)
                        .internalToolExecutionEnabled(false)
                        .build();

        return chatClient()
                .prompt()
                .system(systemPrompt())
                .messages(toSpringMessages(memory))
                .options(options);
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
     * Grounding is built per request so the model always sees the current catalog, not a snapshot
     * baked in at startup.
     */
    private String systemPrompt() {
        return AiPrompts.SYSTEM + "\n" + groundingProvider.facts();
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
