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
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.domain.RunInterruptedException;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.assistant.domain.ToolArgumentsRejectedException;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.logging.MdcContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * <p>- arguments the server cannot run are handed back to the model as one more turn and re-asked
 * exactly once; a second refusal runs with the arguments the schema requires and says so in the
 * reply (ADR-0016). A refused call never leaves the customer with a blank, and never leaves them
 * not knowing the answer they got was a fallback.
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
        return executeTurn(
                context,
                memory ->
                        aiAssistantPort.respond(
                                customerId, context.conversation().getId(), memory));
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
    public AssistantResult executeTurn(
            TurnContext context, Function<List<ChatMessage>, AssistantTurn> modelCall) {
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
     *
     * <p>{@code modelCall} is handed the memory to answer into, which is what lets this method
     * re-ask once when the model gives the server readonly arguments it cannot run (ADR-0016).
     */
    public AssistantResult executeTurn(
            TurnContext context,
            Consumer<Run> onRunOpen,
            Function<List<ChatMessage>, AssistantTurn> modelCall) {
        // The turn-scoped half of the correlation ids: conversationId before the Run exists,
        // runId once it does (US-8.5). They stay in the MDC for the turn's whole duration,
        // so every log line the turn writes -- here or on a streaming worker that carried
        // the request's ids in -- names customer, conversation and run together.
        MdcContext.put(MdcContext.CONVERSATION_ID, context.conversation().getId());
        try {
            Run run = runRepository.save(Run.start(context.conversation().getId()));
            MdcContext.put(MdcContext.RUN_ID, run.id());
            onRunOpen.accept(run);
            AssistantTurn turn = null;
            AssistantResult result = null;
            try {
                turn = modelCall.apply(context.memory());
                ToolArgumentsRejectedException rejection = null;
                try {
                    result = complete(context, turn, StrictArgs.REJECT);
                } catch (ToolArgumentsRejectedException ex) {
                    // 只在这里接住，接着的处理留在 try 的正文里：兜底那一次自己也可能失手，
                    // 而它必须走下面同一条失败出口——Run 不能因为一次修复失败而留在 RUNNING。
                    rejection = ex;
                }
                if (rejection != null) {
                    // 顾客看不见被丢弃的那一次调用（模型一个字都没吐），所以它留下一行失败，重问的那次另记。
                    // 已经开口了就不重问：流式那边顾客已经看见半截话，第二段开场白比按错参数更糟。
                    if (turn.text().isBlank()) {
                        recordFailure(context, turn, rejection);
                        turn = modelCall.apply(observation(context.memory(), rejection));
                    }
                    // 有界：只重问这一次。仍然非法就不再问，改用安全参数收尾并声明（ADR-0016）。
                    result = complete(context, turn, StrictArgs.REPAIR);
                }
            } catch (RunCancelledException ex) {
                persistHalfTurn(
                        context,
                        ex.partial(),
                        null,
                        AiInvocationOutcome.CANCELLED,
                        CANCELLED_REASON);
                runRepository.save(
                        run.finish(RunStatus.CANCELLED, CANCELLED_REASON, Instant.now()));
                throw ex;
            } catch (RunInterruptedException ex) {
                // The customer did not ask for this, but the words already streamed were already
                // seen:
                // they land in the transcript, marked as cut off, so the reply is never a blank.
                persistHalfTurn(
                        context,
                        ex.partial(),
                        INTERRUPTED_SUFFIX,
                        AiInvocationOutcome.ERROR,
                        INTERRUPTED_REASON);
                runRepository.save(
                        run.finish(RunStatus.INTERRUPTED, INTERRUPTED_REASON, Instant.now()));
                throw ex;
            } catch (RuntimeException ex) {
                // The model call may never have happened (null turn) or may have answered and been
                // billed before the turn could not be completed -- recordFailure tells the two
                // apart.
                recordFailure(context, turn, ex);
                runRepository.save(run.finish(RunStatus.FAILED, reasonOf(ex), Instant.now()));
                throw ex;
            }
            // Closing the Run sits OUTSIDE the catch on purpose: a failure to write that row must
            // not
            // be mistaken for a failed turn, which would record an ERROR audit row for a reply that
            // did land. If this throws, the Run is left RUNNING for the startup reconciliation.
            runRepository.save(run.finish(RunStatus.COMPLETED, null, Instant.now()));
            return result;
        } finally {
            MdcContext.remove(MdcContext.CONVERSATION_ID, MdcContext.RUN_ID);
        }
    }

    /** Recorded on a Run the customer stopped, so the row explains itself. */
    public static final String CANCELLED_REASON = "stopped by the customer";

    /** Recorded on a Run whose upstream died mid-reply; the half text has been preserved. */
    public static final String INTERRUPTED_REASON =
            "upstream failed mid-reply; partial text preserved";

    /** Appended to the half reply so the transcript itself says the answer was cut off. */
    public static final String INTERRUPTED_SUFFIX = "\n\n[生成中断]";

    /** 写在"按默认范围重跑过"的回复最前面的一句（ADR-0016）。降级不能是静悄悄的：顾客问最近一单、拿回最近 二十张，那仍然是一个能读的答案，但他得知道自己拿到的是哪一个。 */
    public static final String SAFE_DEFAULT_NOTE = "[检索参数有误，已按默认范围回答]";

    /**
     * A half turn still owes the transcript its text and the audit its row (ADR-0012): CANCELLED
     * for a stop, an annotated message plus ERROR for a mid-reply upstream death. What is
     * deliberately NOT done is anything the turn had not yet reached: no tool results, no
     * confirmation cards -- those are only ever produced by {@link #complete}, which neither a stop
     * nor an interruption reaches.
     */
    private void persistHalfTurn(
            TurnContext context,
            AssistantTurn partial,
            String suffix,
            AiInvocationOutcome outcome,
            String reason) {
        String half = partial == null ? "" : partial.text();
        if (!half.isBlank()) {
            conversationService.appendAssistantMessage(
                    context.conversation().getId(), half + (suffix == null ? "" : suffix));
        }
        recordInvocation(
                AiInvocation.record(
                        context.conversation().getId(),
                        context.conversation().getCustomerId(),
                        partial == null ? null : partial.model(),
                        partial == null ? null : partial.usage().promptTokens(),
                        partial == null ? null : partial.usage().completionTokens(),
                        partial == null ? null : partial.latencyMs(),
                        outcome,
                        reason,
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
     *
     * <p>Illegal readonly arguments are repaired here, so a turn always yields something the
     * customer can read (ADR-0016) -- see {@link #complete(TurnContext, AssistantTurn,
     * StrictArgs)}.
     */
    public AssistantResult complete(TurnContext context, AssistantTurn turn) {
        return complete(context, turn, StrictArgs.REPAIR);
    }

    /** What to do when a readonly tool refuses the model's arguments. */
    private enum StrictArgs {
        /**
         * Hand the refusal back up: {@code executeTurn} may still feed it to the model and re-ask.
         */
        REJECT,
        /** Run again with only the arguments the schema requires, and declare the fallback. */
        REPAIR
    }

    private AssistantResult complete(
            TurnContext context, AssistantTurn turn, StrictArgs strictArgs) {
        Conversation conversation = context.conversation();
        Long customerId = conversation.getCustomerId();

        // 模型走样（US-1.11）：白名单外的工具名，或既没有工具调用、也没有一个字。两种都由服务端
        // 确定性收场。拒绝执行不变：整轮算走样——先扫描再动手，同轮里合法的调用也不跑。
        for (ProposedToolCall call : turn.toolCalls()) {
            if (toolRegistry.find(call.name()).isEmpty()) {
                return offScript(context, turn, call.name());
            }
        }
        if (turn.toolCalls().isEmpty() && turn.text().isBlank()) {
            return offScript(context, turn, null);
        }

        List<ExecutedTool> executed = new ArrayList<>();
        List<PendingAction> pendingActions = new ArrayList<>();
        boolean repaired = false;

        for (ProposedToolCall call : turn.toolCalls()) {
            ToolSpec spec =
                    toolRegistry
                            .find(call.name())
                            // 走样的轮早已在上面的扫描里掉头；查不到是我们自己的 bug，不是输入问题。
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.INTERNAL_ERROR,
                                                    "unregistered tool survived the off-script scan: "
                                                            + call.name()));
            if (spec.mutating()) {
                pendingActions.add(propose(customerId, conversation.getId(), spec, call));
                continue;
            }
            ToolResult result;
            try {
                result = spec.handler().execute(customerId, call.argumentsJson());
            } catch (BusinessException ex) {
                // 只读这一侧才有"回喂"可谈：变更那一条要等顾客确认，非法参数在确认时才被服务端挡下（ADR-0001）。
                if (ex.errorCode() != ErrorCode.VALIDATION_FAILED) {
                    throw ex;
                }
                if (strictArgs == StrictArgs.REJECT) {
                    throw new ToolArgumentsRejectedException(
                            spec.name(), call.argumentsJson(), ex.getMessage(), ex);
                }
                // 兜底：只按 schema 必需的那几个参数重跑。模型写错的那个（limit）正好是被丢掉的那个，
                // 于是这一问落在服务端的默认范围上——顾客拿到能读的答案，而回复里写着这是哪一个。
                result =
                        spec.handler()
                                .execute(customerId, safeArguments(spec, call.argumentsJson()));
                repaired = true;
            }
            executed.add(
                    new ExecutedTool(
                            spec.name(),
                            result.payloadJson(),
                            spec.report().describe(result.payloadJson())));
        }

        String reply = renderReadonly(turn, executed);
        if (repaired && !reply.isBlank()) {
            reply = SAFE_DEFAULT_NOTE + "\n\n" + reply;
        }
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
     * 模型走样时的收场（US-1.11）：顾客读到的必须是一句话，而不是一个错误码或沉默。模型写过的话 保留在前（流式那边已经流出去了，不回撤），后面接 {@link
     * AiPrompts#OFF_SCRIPT_REPLY}；一个 字都没写就只落那一句。拒绝执行不变：这里不碰任何 handler，也不提议任何卡片。
     *
     * <p>"模型走样"与"上游故障"在审计里各说各的：走样记一行 ERROR、原因写明是哪一种，而故障照旧 抛 {@link AiUpstreamException} 以 502
     * 收场。Run 不在失败通道上——顾客拿到了能读的回复， 这一轮就是收尾了的。
     */
    private AssistantResult offScript(
            TurnContext context, AssistantTurn turn, String unregisteredTool) {
        Conversation conversation = context.conversation();
        String reason =
                unregisteredTool != null
                        ? "model called an unregistered tool: " + unregisteredTool
                        : "model returned neither text nor a tool call";
        String reply =
                turn.text().isBlank()
                        ? AiPrompts.OFF_SCRIPT_REPLY
                        : turn.text().trim() + "\n\n" + AiPrompts.OFF_SCRIPT_REPLY;
        conversationService.appendAssistantMessage(conversation.getId(), reply);
        // 这次调用记 ERROR 而不是 OK：它已经发生、也计了费，却没有一个字被采用。
        recordFailure(context, turn, new BusinessException(ErrorCode.UPSTREAM_AI_ERROR, reason));
        return new AssistantResult(
                conversation.getId(),
                reply,
                List.of(),
                List.of(),
                turn.usage(),
                turn.model(),
                turn.latencyMs());
    }

    /**
     * Records a model call that did not produce a usable result. Called from the blocking path's
     * catches, from the streaming endpoint, and from the off-script close-out (the model answered
     * but nothing in its turn could be used), so a failed or unusable call is auditable on both
     * transports.
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

    /**
     * The model's own words, plus how the server describes what the readonly tools found.
     *
     * <p>The payload itself never reaches the transcript (ADR-0015): the report is the whole of
     * what the customer reads, and it carries its own lead-in precisely so that a turn whose model
     * wrote nothing still answers the question. The report is also what memory replays to the next
     * turn, which is why the identifiers and amounts have to survive the rendering.
     */
    private String renderReadonly(AssistantTurn turn, List<ExecutedTool> executed) {
        List<String> blocks = new ArrayList<>();
        if (!turn.text().isBlank()) {
            blocks.add(turn.text().trim());
        }
        for (ExecutedTool tool : executed) {
            if (!tool.rendered().isBlank()) {
                blocks.add(tool.rendered());
            }
        }
        return String.join("\n\n", blocks);
    }

    /**
     * 把一次被拒的调用接在这轮记忆之后，作为重问的观察（ADR-0016）。
     *
     * <p>它不是顾客说的话，也不是助手说的话，而是一次工具结果，所以走 {@link Role#TOOL}：适配层把它标成 {@code [Tool
     * result]}，系统提示词里那条规则教模型怎么对待被拒的调用。
     *
     * <p>这条记忆只活在这次重问里，绝不落库——顾客没说过它，也不该在历史里读到服务器的一句报错。
     */
    private List<ChatMessage> observation(
            List<ChatMessage> memory, ToolArgumentsRejectedException rejection) {
        List<ChatMessage> withObservation = new ArrayList<>(memory);
        withObservation.add(new ChatMessage(Role.TOOL, observationJson(rejection)));
        return List.copyOf(withObservation);
    }

    /** 机器话写给机器看：哪个工具、那串参数、服务器自己的说法，外加一句"什么都没跑成"。 */
    private String observationJson(ToolArgumentsRejectedException rejection) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("tool", rejection.toolName());
        observation.put("arguments", readJson(rejection.argumentsJson()));
        observation.put("error", rejection.reason());
        observation.put("executed", false);
        try {
            return objectMapper.writeValueAsString(observation);
        } catch (JsonProcessingException ex) {
            // 走到这里几乎不可能：值都是刚读回来的节点。真发生了也不能让重问失败，退回服务器那句话。
            return String.valueOf(rejection.reason());
        }
    }

    /**
     * 只留下 schema 声明必需的参数（ADR-0016）。写错的通常正是多出来的那一个（{@code limit}），丢掉它
     * 就回到服务端的默认范围；必需的键留着——缺了它们工具本来就跑不成，那时这一轮该报的是失败，不是降级。
     */
    private String safeArguments(ToolSpec spec, String argumentsJson) {
        JsonNode arguments = readJson(argumentsJson);
        Map<String, Object> safe = new LinkedHashMap<>();
        for (JsonNode key : readJson(spec.inputSchemaJson()).path("required")) {
            String name = key.asText();
            if (arguments.has(name)) {
                safe.put(name, arguments.get(name));
            }
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    /** 读一串 JSON；读不通就当作字符串原样带过去——模型写坏的参数正是要让它自己看见的那样。 */
    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.getNodeFactory().textNode(json);
        }
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
