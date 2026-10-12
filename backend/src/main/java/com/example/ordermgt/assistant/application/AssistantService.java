package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestration, and the place where the AI/business decision is actually made:
 *
 *  - the model runs OUTSIDE any transaction (ADR-0002); the reads and writes around it are
 *    short transactions of their own,
 *  - the model sees the last N turns of this conversation and nothing else,
 *  - readonly tool calls are executed against the business services,
 *  - mutating tool calls are NOT executed -- each becomes a PendingAction plus the card the
 *    customer has to confirm (ADR-0001). This class has no way to run a mutating handler.
 *
 * Deliberately NOT @Transactional: an AI call inside a transaction would hold a database
 * connection across a multi-second network call.
 */
@Service
public class AssistantService {

    private final AiAssistantPort aiAssistantPort;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final PendingActionRepository pendingActionRepository;
    private final ActionCardFactory actionCardFactory;
    private final int memoryWindow;
    private final Duration actionTtl;

    public AssistantService(AiAssistantPort aiAssistantPort,
                            ToolRegistry toolRegistry,
                            ConversationService conversationService,
                            PendingActionRepository pendingActionRepository,
                            ActionCardFactory actionCardFactory,
                            @Value("${assistant.memory.window:20}") int memoryWindow,
                            @Value("${assistant.action.ttl-minutes:15}") long actionTtlMinutes) {
        this.aiAssistantPort = aiAssistantPort;
        this.toolRegistry = toolRegistry;
        this.conversationService = conversationService;
        this.pendingActionRepository = pendingActionRepository;
        this.actionCardFactory = actionCardFactory;
        this.memoryWindow = memoryWindow;
        this.actionTtl = Duration.ofMinutes(actionTtlMinutes);
    }

    /**
     * @param conversationId null starts a brand new conversation (the "新对话" path).
     */
    public AssistantResult chat(Long customerId, Long conversationId, String message) {
        TurnContext context = prepare(customerId, conversationId, message);
        AssistantTurn turn = aiAssistantPort.respond(customerId, context.memory());
        return complete(context, turn);
    }

    /**
     * The part of a turn that happens before the model is called: pick or start the
     * conversation, record what the customer said, and assemble the memory window.
     *
     * Split out for the streaming endpoint, which needs the same setup but obtains the turn
     * incrementally.
     */
    public TurnContext prepare(Long customerId, Long conversationId, String message) {
        Conversation conversation = conversationId == null
                ? conversationService.start(customerId)
                : conversationService.getOwned(customerId, conversationId);

        conversationService.appendCustomerMessage(customerId, conversation.getId(), message);
        List<ChatMessage> memory = conversationService.memory(conversation.getId(), memoryWindow);
        return new TurnContext(conversation, memory);
    }

    /**
     * The part that happens after: classify the tool calls, execute the readonly ones,
     * turn the mutating ones into confirmable cards, and persist the turn.
     */
    public AssistantResult complete(TurnContext context, AssistantTurn turn) {
        Conversation conversation = context.conversation();
        Long customerId = conversation.getCustomerId();

        List<ExecutedTool> executed = new ArrayList<>();
        List<PendingAction> proposals = new ArrayList<>();

        for (ProposedToolCall call : turn.toolCalls()) {
            ToolSpec spec = toolRegistry.find(call.name())
                    .orElseThrow(() -> new BusinessException(ErrorCode.UPSTREAM_AI_ERROR,
                            "模型调用了未注册的工具: " + call.name()));
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
            Message cardMessage = conversationService.appendAssistantMessage(
                    conversation.getId(), card.summary(), proposal.getId());
            conversationService.linkActionToMessage(proposal, cardMessage.id());
            cards.add(card);
        }

        return new AssistantResult(conversation.getId(), reply, executed, cards, turn.usage());
    }

    /** A prepared turn: the conversation is known and the model has not been called yet. */
    public record TurnContext(Conversation conversation, List<ChatMessage> memory) {
    }

    /**
     * Records the proposal. Nothing about it is executed here -- the handler is only
     * reachable from ActionConfirmationService, once the customer confirms.
     */
    private PendingAction propose(Long customerId, Long conversationId, ToolSpec spec, ProposedToolCall call) {
        try {
            PendingAction action = PendingAction.propose(customerId, conversationId, spec.name(),
                    call.argumentsJson(), actionCardFactory.summaryOf(spec.name(), call.argumentsJson()),
                    actionTtl);
            return pendingActionRepository.save(action);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "模型给出的操作参数不合法，已拒绝: " + ex.getMessage());
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
}
