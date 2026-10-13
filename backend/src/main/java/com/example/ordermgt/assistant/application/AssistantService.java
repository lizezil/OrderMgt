package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestration, and the place where the AI/business decision is actually made:
 *
 * <p>- the model runs OUTSIDE any transaction (ADR-0002); the reads and writes around it are short
 * transactions of their own, - the model sees the last N turns of this conversation and nothing
 * else, - readonly tool calls are executed against the business services, - mutating tool calls are
 * NOT executed -- each becomes a PendingAction plus the card the customer has to confirm
 * (ADR-0001). This class has no way to run a mutating handler.
 *
 * <p>Deliberately NOT @Transactional: an AI call inside a transaction would hold a database
 * connection across a multi-second network call.
 *
 * <p>It is also where every model call is written to {@code ai_invocations} -- successes here, and
 * failures through {@link #recordFailure} -- because this is the layer that knows the conversation
 * the call belonged to while the adapter knows the model and the latency.
 */
@Slf4j
@Service
public class AssistantService {

    private final AiAssistantPort aiAssistantPort;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final PendingActionRepository pendingActionRepository;
    private final ActionCardFactory actionCardFactory;
    private final AiInvocationRepository aiInvocationRepository;
    private final ObjectMapper objectMapper;
    private final int memoryWindow;
    private final Duration actionTtl;

    public AssistantService(
            AiAssistantPort aiAssistantPort,
            ToolRegistry toolRegistry,
            ConversationService conversationService,
            PendingActionRepository pendingActionRepository,
            ActionCardFactory actionCardFactory,
            AiInvocationRepository aiInvocationRepository,
            ObjectMapper objectMapper,
            @Value("${assistant.memory.window:20}") int memoryWindow,
            @Value("${assistant.action.ttl-minutes:15}") long actionTtlMinutes) {
        this.aiAssistantPort = aiAssistantPort;
        this.toolRegistry = toolRegistry;
        this.conversationService = conversationService;
        this.pendingActionRepository = pendingActionRepository;
        this.actionCardFactory = actionCardFactory;
        this.aiInvocationRepository = aiInvocationRepository;
        this.objectMapper = objectMapper;
        this.memoryWindow = memoryWindow;
        this.actionTtl = Duration.ofMinutes(actionTtlMinutes);
    }

    /**
     * @param conversationId null starts a brand new conversation (the "新对话" path).
     */
    public AssistantResult chat(Long customerId, Long conversationId, String message) {
        TurnContext context = prepare(customerId, conversationId, message);
        AssistantTurn turn;
        try {
            turn = aiAssistantPort.respond(customerId, context.memory());
        } catch (RuntimeException ex) {
            recordFailure(context, null, ex);
            throw ex;
        }
        try {
            return complete(context, turn);
        } catch (RuntimeException ex) {
            // The model call already happened and was billed, so a turn that cannot be completed
            // (unregistered tool, invalid arguments, a readonly handler blowing up) still owes an
            // audit row -- with the turn's model and latency, which the exception does not carry.
            recordFailure(context, turn, ex);
            throw ex;
        }
    }

    /**
     * The part of a turn that happens before the model is called: pick or start the conversation,
     * record what the customer said, and assemble the memory window.
     *
     * <p>Split out for the streaming endpoint, which needs the same setup but obtains the turn
     * incrementally.
     */
    public TurnContext prepare(Long customerId, Long conversationId, String message) {
        Conversation conversation =
                conversationId == null
                        ? conversationService.start(customerId)
                        : conversationService.getOwned(customerId, conversationId);

        conversationService.appendCustomerMessage(customerId, conversation.getId(), message);
        List<ChatMessage> memory = conversationService.memory(conversation.getId(), memoryWindow);
        return new TurnContext(conversation, memory);
    }

    /**
     * The part that happens after: classify the tool calls, execute the readonly ones, turn the
     * mutating ones into confirmable cards, persist the turn, and log the model call.
     */
    public AssistantResult complete(TurnContext context, AssistantTurn turn) {
        Conversation conversation = context.conversation();
        Long customerId = conversation.getCustomerId();

        List<ExecutedTool> executed = new ArrayList<>();
        List<PendingAction> proposals = new ArrayList<>();

        for (ProposedToolCall call : turn.toolCalls()) {
            ToolSpec spec =
                    toolRegistry
                            .find(call.name())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.UPSTREAM_AI_ERROR,
                                                    "model called an unregistered tool: "
                                                            + call.name()));
            if (spec.mutating()) {
                proposals.add(propose(customerId, conversation.getId(), spec, call));
            } else {
                ToolResult result = spec.handler().execute(customerId, call.argumentsJson());
                executed.add(new ExecutedTool(spec.name(), result.payloadJson()));
            }
        }

        String reply = renderReadonly(turn, executed);
        if (!reply.isBlank()) {
            conversationService.appendAssistantMessage(conversation.getId(), reply);
        }

        List<ActionCard> cards = new ArrayList<>();
        for (PendingAction proposal : proposals) {
            ActionCard card = actionCardFactory.card(proposal);
            Message cardMessage =
                    conversationService.appendAssistantMessage(
                            conversation.getId(), card.summary(), proposal.getId());
            conversationService.linkActionToMessage(proposal, cardMessage.id());
            cards.add(card);
        }

        recordSuccess(conversation, turn);

        return new AssistantResult(
                conversation.getId(),
                reply,
                executed,
                cards,
                turn.usage(),
                turn.model(),
                turn.latencyMs());
    }

    /**
     * Records a model call that did not produce a usable result. Called from the blocking path's
     * catches and from the streaming endpoint, so a failed call is auditable on both transports.
     *
     * <p>{@code turn} is null when the call itself failed; when the model did answer but the turn
     * could not be completed, the row keeps the turn's model, latency and token spend -- the call
     * happened and was billed either way, and an audit row reading "unknown" would hide that.
     */
    public void recordFailure(TurnContext context, AssistantTurn turn, Exception ex) {
        AiUpstreamException upstream = ex instanceof AiUpstreamException u ? u : null;
        String model = upstream != null ? upstream.model() : turn == null ? null : turn.model();
        Integer latencyMs =
                upstream != null ? upstream.latencyMs() : turn == null ? null : turn.latencyMs();
        AiInvocationOutcome outcome =
                upstream != null ? upstream.outcome() : AiInvocationOutcome.ERROR;
        auditAccount(
                context == null ? null : context.conversation(),
                model,
                turn == null ? null : turn.usage().promptTokens(),
                turn == null ? null : turn.usage().completionTokens(),
                latencyMs,
                outcome,
                ex.getMessage(),
                null);
    }

    /** A prepared turn: the conversation is known and the model has not been called yet. */
    public record TurnContext(Conversation conversation, List<ChatMessage> memory) {}

    /**
     * Records the proposal. Nothing about it is executed here -- the handler is only reachable from
     * ActionConfirmationService, once the customer confirms.
     */
    private PendingAction propose(
            Long customerId, Long conversationId, ToolSpec spec, ProposedToolCall call) {
        try {
            PendingAction action =
                    PendingAction.propose(
                            customerId,
                            conversationId,
                            spec.name(),
                            call.argumentsJson(),
                            actionCardFactory.summaryOf(spec.name(), call.argumentsJson()),
                            actionTtl);
            return pendingActionRepository.save(action);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "model proposed invalid tool arguments, rejected: " + ex.getMessage());
        }
    }

    /** The model's own words, plus what the readonly tools actually returned. */
    private String renderReadonly(AssistantTurn turn, List<ExecutedTool> executed) {
        StringBuilder sb = new StringBuilder();
        if (!turn.text().isBlank()) {
            sb.append(turn.text().trim());
        }
        for (ExecutedTool tool : executed) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append('[').append(tool.name()).append("] ").append(tool.resultJson());
        }
        return sb.toString();
    }

    private void recordSuccess(Conversation conversation, AssistantTurn turn) {
        auditAccount(
                conversation,
                turn.model(),
                turn.usage().promptTokens(),
                turn.usage().completionTokens(),
                turn.latencyMs(),
                AiInvocationOutcome.OK,
                null,
                toolCallsJson(turn.toolCalls()));
    }

    /**
     * Audit is a side channel: if it cannot be written, the turn still stands. It is never allowed
     * to turn a successful reply into a failed request.
     */
    private void auditAccount(
            Conversation conversation,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            AiInvocationOutcome outcome,
            String error,
            String toolCallsJson) {
        if (conversation == null) {
            return;
        }
        try {
            aiInvocationRepository.save(
                    AiInvocation.record(
                            conversation.getId(),
                            conversation.getCustomerId(),
                            // model is NOT NULL in the table, and a fake turn may not name one:
                            // an audit row reading "unknown" beats losing the row entirely.
                            model == null || model.isBlank() ? "unknown" : model,
                            promptTokens,
                            completionTokens,
                            latencyMs,
                            outcome,
                            truncate(error),
                            toolCallsJson));
        } catch (RuntimeException ex) {
            log.warn("could not write ai_invocations row: {}", ex.getMessage());
        }
    }

    private String toolCallsJson(List<ProposedToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize tool calls for audit: {}", ex.getMessage());
            return null;
        }
    }

    /** The error column is VARCHAR(1000); a long provider message must not fail the insert. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
