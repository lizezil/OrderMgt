package com.example.ordermgt.assistant.infrastructure.ai;

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
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
public class SpringAiAdapter implements AiAssistantPort {

    /** Streaming calls are bounded so a stalled upstream cannot pin a thread forever. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(2);

    private static final String SYSTEM_PROMPT =
            """
            You are an order management assistant. You may call tools to query or change
            a customer's orders.
            Rules:
            - Use only the provided tools; do not invent tools or arguments.
            - Mutating tools (create / cancel / change status) are only "proposals"; the
              real execution happens after the customer confirms, so when calling them say
              in the text what you intend to do.
            - Never guess or fabricate order numbers, skus or amounts; placing an order
              fills in only sku and quantity.
            - Answer in concise English.
            """;

    private final ChatClient chatClient;
    private final ToolRegistry registry;
    private final GroundingProvider groundingProvider;
    private final String model;

    public SpringAiAdapter(
            ChatClient.Builder builder,
            ToolRegistry registry,
            GroundingProvider groundingProvider,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model) {
        this.registry = registry;
        this.groundingProvider = groundingProvider;
        this.model = model;
        this.chatClient = builder.build();
    }

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
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        long start = System.nanoTime();

        try {
            prompt(memory).stream()
                    .chatClientResponse()
                    .doOnNext(
                            response -> {
                                ChatResponse chunk = response.chatResponse();
                                if (chunk == null) {
                                    return;
                                }
                                last.set(chunk);
                                String delta = textOf(chunk);
                                if (!delta.isEmpty()) {
                                    onTextDelta.accept(delta);
                                }
                            })
                    // Belt and braces: `.timeout` errors out on a stalled stream (no delta for two
                    // minutes), `.blockLast` bounds the total wall clock even if the upstream keeps
                    // trickling tokens. Streaming is not retried by the framework.
                    .timeout(STREAM_TIMEOUT)
                    .blockLast(STREAM_TIMEOUT);
        } catch (RuntimeException ex) {
            throw upstreamFailure("respondStreaming", ex, elapsedMs(start));
        }

        // The final chunk of the stream carries the aggregated tool calls, so the turn is
        // built from it exactly like the non-streaming path -- one extraction, two transports.
        return toTurn(last.get(), elapsedMs(start));
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

        return chatClient
                .prompt()
                .system(systemPrompt())
                .messages(toSpringMessages(memory))
                .options(options);
    }

    private AssistantTurn toTurn(ChatResponse chatResponse, int latencyMs) {
        if (chatResponse == null) {
            return new AssistantTurn("", List.of(), AiUsage.EMPTY, model, latencyMs);
        }
        String text = textOf(chatResponse);

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

        return new AssistantTurn(text, calls, usageOf(chatResponse), model, latencyMs);
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
        return SYSTEM_PROMPT + "\n" + groundingProvider.facts();
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
