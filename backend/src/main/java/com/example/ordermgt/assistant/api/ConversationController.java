package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.ConversationView;
import com.example.ordermgt.assistant.application.CustomerRateLimiter;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.security.CurrentCustomer;
import com.example.ordermgt.shared.api.ApiResponse;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>Every route is scoped by the signed-in session's customer id -- a conversation belonging to
 * someone else is indistinguishable from one that does not exist as far as the caller can tell.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/assistant/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final AssistantService assistantService;
    private final ChatSender chatSender;
    private final ConversationRunQueue runQueue;
    private final RunRegistry runRegistry;
    private final RunService runService;
    private final ConversationEventPublisher events;
    private final AiAssistantPort aiAssistantPort;
    private final ObjectMapper objectMapper;
    private final AssistantApiMapper mapper;
    private final CustomerRateLimiter rateLimiter;

    /** 新对话. A conversation that just started has no Run and no card, so its state is IDLE. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> start(@CurrentCustomer Long customerId) {
        ConversationResponse created =
                mapper.toResponse(ConversationView.idle(conversationService.start(customerId)));
        // Other tabs learn about the new conversation from the stream, not from a refresh.
        events.stateChanged(customerId, created.id());
        return ApiResponse.ok(created);
    }

    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(
            @CurrentCustomer Long customerId,
            @RequestParam(name = "includeArchived", defaultValue = "false")
                    boolean includeArchived) {
        return ApiResponse.ok(
                conversationService.listFor(customerId, includeArchived).stream()
                        .map(mapper::toResponse)
                        .toList());
    }

    /** Full history, with each message's card resolved to its current state. */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> history(
            @CurrentCustomer Long customerId, @PathVariable Long conversationId) {
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
            @CurrentCustomer Long customerId, @PathVariable Long conversationId) {
        return ApiResponse.ok(
                conversationService.invocations(customerId, conversationId).stream()
                        .map(mapper::toResponse)
                        .toList());
    }

    @PostMapping("/{conversationId}/messages")
    public ApiResponse<ChatResponse> send(
            @CurrentCustomer Long customerId,
            @PathVariable Long conversationId,
            @RequestHeader(value = IdempotencyGuard.KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(
                chatSender.send(customerId, conversationId, idempotencyKey, request.message()));
    }

    /**
     * Hides a conversation from the default list without deleting it (ADR-0013). Only a quiescent
     * conversation can be archived -- a Run in flight or a live card is a 409, because archiving is
     * visibility, not termination (that is {@link #stopRun}).
     */
    @PostMapping("/{conversationId}/archive")
    public ApiResponse<ConversationResponse> archive(
            @CurrentCustomer Long customerId, @PathVariable Long conversationId) {
        conversationService.archive(customerId, conversationId);
        events.stateChanged(customerId, conversationId);
        return ApiResponse.ok(
                mapper.toResponse(conversationService.view(customerId, conversationId)));
    }

    /** Brings an archived conversation back into the default list. Idempotent. */
    @PostMapping("/{conversationId}/restore")
    public ApiResponse<ConversationResponse> restore(
            @CurrentCustomer Long customerId, @PathVariable Long conversationId) {
        conversationService.restore(customerId, conversationId);
        events.stateChanged(customerId, conversationId);
        return ApiResponse.ok(
                mapper.toResponse(conversationService.view(customerId, conversationId)));
    }

    /**
     * The explicit stop (ADR-0012). Stops a RUNNING Run -- really aborting the upstream model
     * stream -- leaving the half reply as an ordinary ASSISTANT message and the Run CANCELLED.
     * Idempotent: stopping an already-finished Run is a no-op, so a second tab or a double click is
     * harmless. Ownership is checked first, so a stranger cannot stop someone else's run.
     */
    @PostMapping("/{conversationId}/runs/{runId}/stop")
    public ApiResponse<Void> stopRun(
            @CurrentCustomer Long customerId,
            @PathVariable Long conversationId,
            @PathVariable Long runId) {
        conversationService.getOwned(customerId, conversationId);
        runService.stop(conversationId, runId);
        return ApiResponse.ok(null);
    }

    /**
     * Subscribes to a Run that is already in flight: the "switch away and switch back" case
     * (ADR-0014). The first frame is the text accumulated so far, then `token` deltas; several
     * subscribers may watch one Run at once, because each is a read of the same accumulated text
     * rather than an exclusive connection. There is no per-delta cache and no resume -- only the
     * current text is held, so the snapshot is stateless (ADR-0009).
     *
     * <p>A Run that is not live in this process -- already terminal, or owned elsewhere -- is
     * closed at once with a `closed` frame, so a late subscriber is told the truth instead of
     * waiting on a stream that will never speak again.
     */
    @GetMapping(
            value = "/{conversationId}/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEvents(
            @CurrentCustomer Long customerId,
            @PathVariable Long conversationId,
            @PathVariable Long runId) {
        conversationService.getOwned(customerId, conversationId);
        Run run = runService.require(conversationId, runId);

        SseEmitter emitter = new SseEmitter(AiCallPolicy.STREAM_TIMEOUT_MS);
        AtomicBoolean clientGone = new AtomicBoolean(false);

        RunRegistry.Handle live = runRegistry.find(runId).orElse(null);
        if (live == null) {
            // Nothing live here to watch -- the run is terminal, or another process owns it. The
            // connection is closed right away, so a late subscriber is told rather than waiting.
            emitter.onError(error -> clientGone.set(true));
            emitter.onTimeout(() -> clientGone.set(true));
            deliver(emitter, clientGone, "closed", closedPayload(run));
            completeQuietly(emitter);
            return emitter;
        }

        RunRegistry.Handle.Subscriber subscriber =
                new RunRegistry.Handle.Subscriber() {

                    @Override
                    public void delta(String text) {
                        deliver(emitter, clientGone, "token", text);
                    }

                    @Override
                    public void completed() {
                        deliver(emitter, clientGone, "closed", closedPayload(run));
                        completeQuietly(emitter);
                    }
                };
        // A watcher that leaves stops being a subscriber: this connection has no turn holding it,
        // so nothing else would ever remove it before the run ends. One handler for all three ways
        // a connection ends, so none is silently overwritten by the next.
        Runnable stopWatching =
                () -> {
                    clientGone.set(true);
                    live.detach(subscriber);
                };
        emitter.onCompletion(stopWatching);
        emitter.onTimeout(stopWatching);
        emitter.onError(error -> stopWatching.run());

        RunRegistry.Handle.Attached attached = live.attach(subscriber);
        deliver(emitter, clientGone, "snapshot", textPayload(attached.text()));
        if (attached.finished()) {
            deliver(emitter, clientGone, "closed", closedPayload(run));
            completeQuietly(emitter);
        }
        return emitter;
    }

    private String textPayload(String text) {
        try {
            return objectMapper.writeValueAsString(new RunTextResponse(text));
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize the run snapshot: {}", ex.getMessage());
            return "{}";
        }
    }

    private String closedPayload(Run run) {
        try {
            return objectMapper.writeValueAsString(
                    new RunClosedResponse(run.id(), run.status().name()));
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize the run-closed frame: {}", ex.getMessage());
            return "{}";
        }
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
     *
     * <p>And one more: a conversation runs one turn at a time (ADR-0012). If a turn is already in
     * flight here, this request does not fail -- it waits, FIFO, behind {@code
     * assistant.run.queue-depth} others, and a {@code queued} frame tells the caller which line
     * item it got so it can be deleted. Past that depth the answer is 429 {@code QUEUE_FULL},
     * before any frame.
     */
    @GetMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @CurrentCustomer Long customerId,
            @PathVariable Long conversationId,
            @RequestParam String message) {
        // Ownership before admission: a stranger's request must not be able to tell a busy
        // conversation from a quiet one, nor take a slot in its line. 403/404 arrives as JSON,
        // before the response is committed to SSE.
        conversationService.getOwned(customerId, conversationId);
        // Rate limit before the SSE promise: past the customer's quota this answers a plain JSON
        // 429 with Retry-After -- never an error frame on a stream the client already accepted.
        rateLimiter.admit(customerId);

        SseEmitter emitter = new SseEmitter(AiCallPolicy.STREAM_TIMEOUT_MS);
        // The container learns about a dead connection (or the 120s timeout) through these
        // callbacks, often before the next send would fail; either way this one flag decides
        // whether anything else goes on the wire.
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onError(error -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        ConversationRunQueue.Turn turn =
                new ConversationRunQueue.Turn() {

                    @Override
                    public void start() {
                        // "Out of the line, running now": the one moment the waiting client must be
                        // told about, since a silent turn is otherwise indistinguishable from a
                        // turn still waiting for the whole time before its first token.
                        deliver(emitter, clientGone, "started", "{}");
                        streamTurn(emitter, clientGone, customerId, conversationId, message);
                    }

                    @Override
                    public void drop() {
                        deliver(emitter, clientGone, "dropped", "{}");
                        completeQuietly(emitter);
                    }
                };

        ConversationRunQueue.Admission admission;
        try {
            admission = runQueue.submitStreaming(conversationId, turn);
        } catch (RejectedExecutionException ex) {
            log.warn("streaming pool saturated; rejected turn for conversation {}", conversationId);
            throw new BusinessException(
                    ErrorCode.SERVICE_BUSY, "too many streaming turns in flight; retry shortly");
        }
        if (admission instanceof ConversationRunQueue.Admission.Queued queued) {
            deliver(emitter, clientGone, "queued", queuedPayload(queued));
        }
        // The line changed for this conversation: let every open status stream see it, so another
        // tab knows the send is waiting (and can delete it by the item id it was given).
        events.queueChanged(customerId, conversationId);
        return emitter;
    }

    /**
     * Deletes one waiting item from this conversation's line. Deleting is the only way to change
     * the queue -- the line is FIFO and is never reordered (ADR-0012) -- and a deleted item never
     * runs, not even late: its own stream gets a {@code dropped} frame and closes.
     *
     * <p>Idempotent in effect -- repeating it changes nothing more -- but not in response: the
     * design pins a missing item to 404, which is also the honest answer to losing the race where
     * the item reached the front and started between the click and this call.
     */
    @DeleteMapping("/{conversationId}/queue/{itemId}")
    public ApiResponse<Void> cancelQueued(
            @CurrentCustomer Long customerId,
            @PathVariable Long conversationId,
            @PathVariable String itemId) {
        conversationService.getOwned(customerId, conversationId);
        if (!runQueue.remove(conversationId, itemId)) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND, "no queued turn " + itemId + " in this conversation");
        }
        events.queueChanged(customerId, conversationId);
        return ApiResponse.ok(null);
    }

    /** The itemId is what DELETE needs, so this frame is what makes a line item removable. */
    private String queuedPayload(ConversationRunQueue.Admission.Queued queued) {
        try {
            return objectMapper.writeValueAsString(
                    new QueuedTurnResponse(queued.itemId(), queued.position()));
        } catch (JsonProcessingException ex) {
            // Two primitives cannot fail to serialize; if they somehow did, an empty frame reads as
            // "queued, but not removable" rather than breaking the stream.
            log.warn("could not serialize the queued frame: {}", ex.getMessage());
            return "{}";
        }
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
     *
     * <p>The turn holds this conversation's slot for its whole life, which is what keeps a second
     * turn in line (ADR-0012); the release happens whatever the outcome, so a failed turn hands the
     * slot to the next waiter instead of wedging the conversation.
     */
    void streamTurn(
            SseEmitter emitter,
            AtomicBoolean clientGone,
            Long customerId,
            Long conversationId,
            String message) {
        // The live run's handle, opened the moment the Run row exists (inside executeTurn) and
        // closed when this turn ends. It carries the cancellation signal a stop trips and the text
        // a reattach replays, and it exists only for as long as the turn does.
        AtomicReference<RunRegistry.Handle> handle = new AtomicReference<>();
        try {
            AssistantService.TurnContext context =
                    assistantService.prepare(customerId, conversationId, message);

            AssistantResult result =
                    assistantService.executeTurn(
                            context,
                            run -> {
                                handle.set(runRegistry.open(run.id()));
                                // The run id is minted here, after the stream is open, so it is
                                // sent on its own frame: it is what the client's stop button needs.
                                deliver(emitter, clientGone, "run", runPayload(run.id()));
                                // The conversation is RUNNING now: tell every open status stream.
                                events.stateChanged(customerId, conversationId);
                            },
                            () ->
                                    aiAssistantPort.respondStreaming(
                                            customerId,
                                            context.memory(),
                                            delta -> {
                                                RunRegistry.Handle live = handle.get();
                                                if (live != null) {
                                                    live.append(delta);
                                                }
                                                deliver(emitter, clientGone, "token", delta);
                                            },
                                            cancellationOf(handle)));
            deliver(
                    emitter,
                    clientGone,
                    "done",
                    objectMapper.writeValueAsString(mapper.toResponse(result)));
            completeQuietly(emitter);
        } catch (RunCancelledException cancelled) {
            // A stop, not a failure: the half reply is already on screen (the tokens the client
            // received) and now persisted; close with a frame that says so. No `error`, no toast.
            deliver(emitter, clientGone, "cancelled", stoppedPayload(cancelled));
            completeQuietly(emitter);
        } catch (Exception ex) {
            // The Run, its audit row and (on the blocking path) the rethrow were handled inside
            // executeTurn; this transport only owes whoever is still listening an `error` event.
            log.warn(
                    "streaming turn failed for conversation {}: {}",
                    conversationId,
                    ex.getMessage());
            deliver(emitter, clientGone, "error", safeErrorMessage(ex));
            completeQuietly(emitter);
        } finally {
            // Close the handle before handing the slot on: a reattach to this run must find it
            // already gone, and the next waiter must not see a dead run's text.
            RunRegistry.Handle live = handle.get();
            if (live != null) {
                runRegistry.close(live.runId());
            }
            runQueue.finished(conversationId);
            // The turn ended and the line advanced: push both, so every tab sees the new state
            // (IDLE / WAITING_APPROVAL, or still RUNNING if a waiter just took over) and the new
            // queue in one go.
            events.stateChanged(customerId, conversationId);
            events.queueChanged(customerId, conversationId);
        }
    }

    /** The signal the model call polls, read off the live handle as soon as it exists. */
    private static RunCancellation cancellationOf(AtomicReference<RunRegistry.Handle> handle) {
        return () -> {
            RunRegistry.Handle live = handle.get();
            return live != null && live.isCancelled();
        };
    }

    /**
     * What the wire is told when a stream fails. A {@link BusinessException} carries a message
     * written for the caller and travels verbatim; anything else is an internal detail -- raw SQL
     * or framework text (and the 404/403 wording that would let a stranger probe conversation ids)
     * stays in the log, and the client gets one stable sentence it can show.
     */
    private static String safeErrorMessage(Exception ex) {
        if (ex instanceof BusinessException business) {
            return business.getMessage();
        }
        return "the assistant could not finish this reply; please retry";
    }

    private String runPayload(Long runId) {
        try {
            return objectMapper.writeValueAsString(new RunRefResponse(runId));
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize the run frame: {}", ex.getMessage());
            return "{}";
        }
    }

    private String stoppedPayload(RunCancelledException cancelled) {
        String half = cancelled.partial() == null ? "" : cancelled.partial().text();
        try {
            return objectMapper.writeValueAsString(new RunStoppedResponse(half));
        } catch (JsonProcessingException ex) {
            log.warn("could not serialize the cancelled frame: {}", ex.getMessage());
            return "{}";
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
