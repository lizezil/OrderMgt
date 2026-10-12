package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.GroundingProvider;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
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
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class SpringAiAdapter implements AiAssistantPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAdapter.class);

    /** Streaming calls are bounded so a stalled upstream cannot pin a thread forever. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(2);

    private static final String SYSTEM_PROMPT = """
            你是订单管理助手。你可以调用工具来查询或变更客户的订单。
            规则：
            - 只能使用已提供的工具，不要臆造工具或参数。
            - 变更类工具（创建/取消/改状态）只是"提议"，真实执行由客户确认后完成，
              所以调用它们时请在文本里说明你打算做什么。
            - 绝不要猜测或编造订单号、商品编号或金额；下单只填 sku 和数量。
            - 用简洁的中文回答。
            """;

    private final ChatClient chatClient;
    private final ToolRegistry registry;
    private final GroundingProvider groundingProvider;

    public SpringAiAdapter(ChatClient.Builder builder, ToolRegistry registry,
                           GroundingProvider groundingProvider) {
        this.registry = registry;
        this.groundingProvider = groundingProvider;
        this.chatClient = builder.build();
    }

    @Override
    public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
        ChatClientResponse clientResponse = callingUpstream("respond",
                () -> prompt(memory).call().chatClientResponse());
        return toTurn(clientResponse == null ? null : clientResponse.chatResponse());
    }

    @Override
    public AssistantTurn respondStreaming(Long customerId, List<ChatMessage> memory,
                                          Consumer<String> onTextDelta) {
        AtomicReference<ChatResponse> last = new AtomicReference<>();

        callingUpstream("respondStreaming", () -> {
            prompt(memory).stream().chatClientResponse()
                    .doOnNext(response -> {
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
                    .blockLast(STREAM_TIMEOUT);
            return null;
        });

        // The final chunk of the stream carries the aggregated tool calls, so the turn is
        // built from it exactly like the non-streaming path -- one extraction, two transports.
        return toTurn(last.get());
    }

    /**
     * The model call is the one dependency here that can fail for reasons that are not this
     * service's fault. Translating it keeps a lapsed key or a provider outage from arriving
     * as a generic 500, which would read as a defect in our own code.
     */
    private <T> T callingUpstream(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("upstream AI call failed during {}: {}", operation, ex.getMessage());
            throw new BusinessException(ErrorCode.UPSTREAM_AI_ERROR,
                    ErrorCode.UPSTREAM_AI_ERROR.defaultMessage(), ex);
        }
    }

    private ChatClient.ChatClientRequestSpec prompt(List<ChatMessage> memory) {
        ToolCallback[] callbacks = registry.all().stream()
                .map(RegistryToolCallback::new)
                .toArray(ToolCallback[]::new);

        // The approval gate: no tool may run inside the model call.
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        return chatClient.prompt()
                .system(systemPrompt())
                .messages(toSpringMessages(memory))
                .options(options);
    }

    private AssistantTurn toTurn(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return new AssistantTurn("", List.of(), AiUsage.EMPTY);
        }
        String text = textOf(chatResponse);

        List<ProposedToolCall> calls = new ArrayList<>();
        if (chatResponse.hasToolCalls()) {
            for (AssistantMessage.ToolCall call : chatResponse.getResult().getOutput().getToolCalls()) {
                boolean mutating = registry.find(call.name()).map(ToolSpec::mutating).orElse(true);
                if (registry.find(call.name()).isEmpty()) {
                    log.warn("model asked for an unregistered tool: {}", call.name());
                }
                calls.add(new ProposedToolCall(call.name(), call.arguments(), mutating));
            }
        }

        return new AssistantTurn(text, calls, usageOf(chatResponse));
    }

    private static String textOf(ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse.getResult())
                .map(r -> r.getOutput())
                .map(AssistantMessage::getText)
                .orElse("");
    }

    private static AiUsage usageOf(ChatResponse chatResponse) {
        Usage usage = chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
        return usage == null
                ? AiUsage.EMPTY
                : new AiUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /**
     * Grounding is built per request so the model always sees the current catalog, not a
     * snapshot baked in at startup.
     */
    private String systemPrompt() {
        return SYSTEM_PROMPT + "\n" + groundingProvider.facts();
    }

    private List<Message> toSpringMessages(List<ChatMessage> memory) {
        List<Message> messages = new ArrayList<>(memory.size());
        for (ChatMessage turn : memory) {
            messages.add(switch (turn.role()) {
                case CUSTOMER -> new UserMessage(nullToEmpty(turn.content()));
                case ASSISTANT -> new AssistantMessage(nullToEmpty(turn.content()));
                case TOOL -> new UserMessage("[工具结果] " + nullToEmpty(turn.content()));
            });
        }
        return messages;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
