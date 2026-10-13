package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.ConversationView;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.shared.api.ApiResponse;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Conversations are the unit of memory. "新对话" is simply POST here; the SPA keeps a sidebar of these
 * so a customer can come back to an earlier thread.
 *
 * <p>Every route is scoped by X-Customer-Id -- a conversation belonging to someone else is
 * indistinguishable from one that does not exist as far as the caller can tell.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/assistant/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final AssistantService assistantService;
    private final ChatSender chatSender;
    private final AiAssistantPort aiAssistantPort;
    private final ObjectMapper objectMapper;
    private final AssistantApiMapper mapper;

    @Qualifier("assistantStreamExecutor")
    private final Executor streamExecutor;

    /** 新对话. A conversation that just started has no Run and no card, so its state is IDLE. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> start(
            @RequestHeader("X-Customer-Id") Long customerId) {
        return ApiResponse.ok(
                mapper.toResponse(ConversationView.idle(conversationService.start(customerId))));
    }

    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(
            @RequestHeader("X-Customer-Id") Long customerId) {
        return ApiResponse.ok(
                conversationService.listFor(customerId).stream().map(mapper::toResponse).toList());
    }

    /** Full history, with each message's card resolved to its current state. */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> history(
            @RequestHeader("X-Customer-Id") Long customerId, @PathVariable Long conversationId) {
        return ApiResponse.ok(
                conversationService.history(customerId, conversationId).stream()
                        .map(mapper::toResponse)
                        .toList());
    }

    /**
     * The model calls behind this conversation: model, latency, outcome, when. Scoped by the same
     * ownership rule as history, so someone else's conversation is a 403, not an outline of their
     * traffic.
     */
    @GetMapping("/{conversationId}/invocations")
    public ApiResponse<List<InvocationResponse>> invocations(
            @RequestHeader("X-Customer-Id") Long customerId, @PathVariable Long conversationId) {
        return ApiResponse.ok(
                conversationService.invocations(customerId, conversationId).stream()
                        .map(mapper::toResponse)
                        .toList());
    }

    @PostMapping("/{conversationId}/messages")
    public ApiResponse<ChatResponse> send(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long conversationId,
            @RequestHeader(value = IdempotencyGuard.KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(
                chatSender.send(customerId, conversationId, idempotencyKey, request.message()));
    }

    /**
     * SSE: the model's prose arrives as `token` events, then one `done` event carrying the same
     * payload as the JSON endpoint (cards included). `error` closes a failed stream.
     *
     * <p>Three things are deliberate here: - it is a GET with the message in the query string,
     * because EventSource cannot POST; - it is NOT idempotent: a stream cannot be replayed, so
     * retries belong on the JSON endpoint with an Idempotency-Key; - it streams text only. A tool
     * turn is atomic by nature, so its card shows up whole in the `done` event -- pretending to
     * stream it would be theatre.
     *
     * <p>A fourth: delivery is best-effort, the turn is not. A client that disappears mid-stream
     * (closed tab, dropped network) only stops receiving events -- the model runs to completion and
     * the turn is persisted either way, so the reply survives for the customer to find on their
     * next visit (see {@link #streamTurn}).
     *
     * <p>The stream runs on a bounded pool (see {@code AsyncConfig}). When it is saturated --
     * {@code max-concurrent} running and the wait queue full -- the rejection is reported as a 503
     * before any event is sent, so a caller finds out immediately instead of holding a connection
     * open for a turn that would only time out in the queue.
     */
    @GetMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long conversationId,
            @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(AiCallPolicy.STREAM_TIMEOUT_MS);
        // The container learns about a dead connection (or the 120s timeout) through these
        // callbacks, often before the next send would fail; either way this one flag decides
        // whether anything else goes on the wire.
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onError(error -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
        try {
            streamExecutor.execute(
                    () -> streamTurn(emitter, clientGone, customerId, conversationId, message));
        } catch (RejectedExecutionException ex) {
            log.warn("streaming pool saturated; rejected turn for conversation {}", conversationId);
            throw new BusinessException(
                    ErrorCode.SERVICE_BUSY, "too many streaming turns in flight; retry shortly");
        }
        return emitter;
    }

    /**
     * Package-private so the disconnect contract can be tested without a container (same seam as
     * {@code SpringAiAdapter#retryBeforeFirstToken}).
     *
     * <p>The contract in one line: a dead connection is a delivery failure, never a turn failure.
     * The customer message was committed by {@code prepare} before the model was called, so a
     * mid-stream disconnect that also aborted the reply would leave every closed tab with a
     * question and no answer in the history. Instead: stop sending, let the model finish, and close
     * the Run -- {@code executeTurn} owns the persistence, the audit row and the Run's terminal
     * state, on this transport exactly as on the blocking one. "Nobody was listening" is a log
     * line, not an audit failure.
     */
    void streamTurn(
            SseEmitter emitter,
            AtomicBoolean clientGone,
            Long customerId,
            Long conversationId,
            String message) {
        try {
            AssistantService.TurnContext context =
                    assistantService.prepare(customerId, conversationId, message);

            AssistantResult result =
                    assistantService.executeTurn(
                            context,
                            () ->
                                    aiAssistantPort.respondStreaming(
                                            customerId,
                                            context.memory(),
                                            delta -> deliver(emitter, clientGone, "token", delta)));
            deliver(
                    emitter,
                    clientGone,
                    "done",
                    objectMapper.writeValueAsString(mapper.toResponse(result)));
            completeQuietly(emitter);
        } catch (Exception ex) {
            // The Run, its audit row and (on the blocking path) the rethrow were handled inside
            // executeTurn; this transport only owes whoever is still listening an `error` event.
            log.warn(
                    "streaming turn failed for conversation {}: {}",
                    conversationId,
                    ex.getMessage());
            deliver(
                    emitter,
                    clientGone,
                    "error",
                    ex.getMessage() == null ? "streaming response failed" : ex.getMessage());
            completeQuietly(emitter);
        }
    }

    /**
     * One event on the wire. The first failed send flips {@code clientGone} and that is the end of
     * delivery -- no exception travels further, because the turn behind it keeps running to
     * completion regardless. A failure here is the transport saying "nobody is listening", which is
     * exactly the case the turn must survive.
     */
    private void deliver(SseEmitter emitter, AtomicBoolean clientGone, String event, String data) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException ex) {
            clientGone.set(true);
            log.info(
                    "client left the stream before \"{}\" ({}); finishing the turn unattended",
                    event,
                    ex.getMessage());
        }
    }

    /**
     * The container may have finished (or failed) the async cycle already; either way we are done.
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            log.debug("could not complete the stream, client probably gone", ex);
        }
    }
}
