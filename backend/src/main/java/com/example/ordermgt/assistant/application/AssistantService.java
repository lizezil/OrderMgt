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
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class AssistantService {

    private final AiAssistantPort aiAssistantPort;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final PendingActionRepository pendingActionRepository;
    private final RunRepository runRepository;
    private final ActionCardFactory actionCardFactory;
    private final AiInvocationRepository aiInvocationRepository;
    private final ObjectMapper objectMapper;

    @Value("${assistant.memory.window:20}")
    private final int memoryWindow;

    /** Configured in minutes (the shape it arrives in); {@link #actionTtl()} is how it is used. */
    @Value("${assistant.action.ttl-minutes:15}")
    private final long actionTtlMinutes;

    /**
     * @param conversationId null starts a brand new conversation (the "新对话" path).
     */
    public AssistantResult chat(Long customerId, Long conversationId, String message) {
        TurnContext context = prepare(customerId, conversationId, message);
        return executeTurn(context, () -> aiAssistantPort.respond(customerId, context.memory()));
    }

    /**
     * One Run, shared by both transports: open a RUNNING row, call the model, persist the turn,
     * then close the Run COMPLETED -- or FAILED with the reason if anything threw (ADR-0011). The
     * model call is the only thing that differs between the blocking and streaming entries, so it
     * is the parameter.
     *
     * <p>The Run opens once the conversation exists, which is what {@code prepare} settled. That is
     * also why an interrupted Run reads as a question with no answer: the customer message was
     * committed there, before the call this row now guards.
     */
    public AssistantResult executeTurn(TurnContext context, Supplier<AssistantTurn> modelCall) {
        return executeTurn(context, run -> {}, modelCall);
    }

    /**
     * The same turn, but the caller is handed the RUNNING Run the moment it exists -- before the
     * model is called. The streaming transport uses this to register the run it can later stop
     * (ADR-0012) and to tell the client the run id it will need for that.
     *
     * <p>A stop is not a failure (ADR-0012): the half text becomes an ordinary ASSISTANT message,
     * the Run closes CANCELLED, the call is audited CANCELLED, and the exception travels on so the
     * transport can close its stream with a {@code cancelled} frame instead of an error.
     */
    public AssistantResult executeTurn(
            TurnContext context, Consumer<Run> onRunOpen, Supplier<AssistantTurn> modelCall) {
        Run run = runRepository.save(Run.start(context.conversation().getId()));
        onRunOpen.accept(run);
        AssistantTurn turn = null;
        AssistantResult result;
        try {
            turn = modelCall.get();
            result = complete(context, turn);
        } catch (RunCancelledException ex) {
            persistHalfTurn(context, ex.partial());
            runRepository.save(run.finish(RunStatus.CANCELLED, CANCELLED_REASON, Instant.now()));
            throw ex;
        } catch (RuntimeException ex) {
            // The model call may never have happened (null turn) or may have answered and been
            // billed before the turn could not be completed -- recordFailure tells the two apart.
            recordFailure(context, turn, ex);
            runRepository.save(run.finish(RunStatus.FAILED, reasonOf(ex), Instant.now()));
            throw ex;
        }
        // Closing the Run sits OUTSIDE the catch on purpose: a failure to write that row must not
        // be mistaken for a failed turn, which would record an ERROR audit row for a reply that
        // did land. If this throws, the Run is left RUNNING for the startup reconciliation.
        runRepository.save(run.finish(RunStatus.COMPLETED, null, Instant.now()));
        return result;
    }

    /** Recorded on a Run the customer stopped, so the row explains itself. */
    public static final String CANCELLED_REASON = "stopped by the customer";

    /**
     * A stopped turn still owes the transcript the half reply and the audit its CANCELLED row
     * (ADR-0012). What is deliberately NOT done is anything the turn had not yet reached: no tool
     * results, no confirmation cards -- those are only ever produced by {@link #complete}, which a
     * stop never reaches.
     */
    private void persistHalfTurn(TurnContext context, AssistantTurn partial) {
        String half = partial == null ? "" : partial.text();
        if (!half.isBlank()) {
            conversationService.appendAssistantMessage(context.conversation().getId(), half);
        }
        recordInvocation(
                AiInvocation.record(
                        context.conversation().getId(),
                        context.conversation().getCustomerId(),
                        partial == null ? null : partial.model(),
                        partial == null ? null : partial.usage().promptTokens(),
                        partial == null ? null : partial.usage().completionTokens(),
                        partial == null ? null : partial.latencyMs(),
                        AiInvocationOutcome.CANCELLED,
                        CANCELLED_REASON,
                        null));
    }

    /** A failure with no message still owes the Run a reason: the exception's own name. */
    private static String reasonOf(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
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
        List<PendingAction> pendingActions = new ArrayList<>();

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
                pendingActions.add(propose(customerId, conversation.getId(), spec, call));
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
        for (PendingAction pendingAction : pendingActions) {
            ActionCard card = actionCardFactory.card(pendingAction);
            Message cardMessage =
                    conversationService.appendAssistantMessage(
                            conversation.getId(), card.summary(), pendingAction.getId());
            conversationService.linkActionToMessage(pendingAction, cardMessage.id());
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
        if (context == null) {
            return;
        }
        AiUpstreamException upstream = ex instanceof AiUpstreamException u ? u : null;
        Conversation conversation = context.conversation();
        // Boxed through locals on purpose: the model call may have failed before it produced a
        // turn, and a ternary that mixes an `int` branch with `null` makes the whole expression
        // `int`-typed -- the null then unboxes into an NPE thrown while already handling a failure,
        // which would both mask the real cause and skip closing the Run.
        Integer promptTokens = turn == null ? null : turn.usage().promptTokens();
        Integer completionTokens = turn == null ? null : turn.usage().completionTokens();
        Integer latencyMs = null;
        if (upstream != null) {
            latencyMs = upstream.latencyMs();
        } else if (turn != null) {
            latencyMs = turn.latencyMs();
        }
        recordInvocation(
                AiInvocation.record(
                        conversation.getId(),
                        conversation.getCustomerId(),
                        upstream != null ? upstream.model() : turn == null ? null : turn.model(),
                        promptTokens,
                        completionTokens,
                        latencyMs,
                        upstream != null ? upstream.outcome() : AiInvocationOutcome.ERROR,
                        ex.getMessage(),
                        null));
    }

    /** A prepared turn: the conversation is known and the model has not been called yet. */
    public record TurnContext(Conversation conversation, List<ChatMessage> memory) {}

    /**
     * Records the pending action. Nothing about it is executed here -- the handler is only
     * reachable from ActionConfirmationService, once the customer confirms.
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
                            actionTtl());
            return pendingActionRepository.save(action);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "model proposed invalid tool arguments, rejected: " + ex.getMessage());
        }
    }

    private Duration actionTtl() {
        return Duration.ofMinutes(actionTtlMinutes);
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
        recordInvocation(
                AiInvocation.record(
                        conversation.getId(),
                        conversation.getCustomerId(),
                        turn.model(),
                        turn.usage().promptTokens(),
                        turn.usage().completionTokens(),
                        turn.latencyMs(),
                        AiInvocationOutcome.OK,
                        null,
                        toolCallsJson(turn.toolCalls())));
    }

    /**
     * Audit is a side channel: if it cannot be written, the turn still stands. It is never allowed
     * to turn a successful reply into a failed request.
     */
    private void recordInvocation(AiInvocation invocation) {
        try {
            aiInvocationRepository.save(invocation);
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
}
