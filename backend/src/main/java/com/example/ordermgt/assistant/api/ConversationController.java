package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.shared.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
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

    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final ConversationService conversationService;
    private final AssistantService assistantService;
    private final IdempotencyGuard idempotencyGuard;
    private final AiAssistantPort aiAssistantPort;
    private final ObjectMapper objectMapper;
    private final AssistantApiMapper mapper;

    @Qualifier("assistantStreamExecutor")
    private final Executor streamExecutor;

    /** 新对话. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> start(
            @RequestHeader("X-Customer-Id") Long customerId) {
        return ApiResponse.ok(mapper.toResponse(conversationService.start(customerId)));
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

    @PostMapping("/{conversationId}/messages")
    public ApiResponse<ChatResponse> send(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long conversationId,
            @RequestHeader(value = IdempotencyGuard.KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody ChatRequest request) {

        ChatResponse response =
                idempotencyGuard.execute(
                        customerId,
                        "SEND_MESSAGE",
                        idempotencyKey,
                        IdempotencyGuard.hashOf(String.valueOf(conversationId), request.message()),
                        () ->
                                mapper.toResponse(
                                        assistantService.chat(
                                                customerId, conversationId, request.message())),
                        ChatResponse.class);
        return ApiResponse.ok(response);
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
     */
    @GetMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long conversationId,
            @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> streamTurn(emitter, customerId, conversationId, message));
        return emitter;
    }

    private void streamTurn(
            SseEmitter emitter, Long customerId, Long conversationId, String message) {
        try {
            AssistantService.TurnContext context =
                    assistantService.prepare(customerId, conversationId, message);

            AssistantTurn turn =
                    aiAssistantPort.respondStreaming(
                            customerId, context.memory(), delta -> emit(emitter, "token", delta));

            AssistantResult result = assistantService.complete(context, turn);
            emit(emitter, "done", objectMapper.writeValueAsString(mapper.toResponse(result)));
            emitter.complete();
        } catch (Exception ex) {
            log.warn(
                    "streaming turn failed for conversation {}: {}",
                    conversationId,
                    ex.getMessage());
            try {
                emit(emitter, "error", ex.getMessage() == null ? "流式响应失败" : ex.getMessage());
            } catch (RuntimeException suppressed) {
                log.debug("could not report the stream error, client probably gone", suppressed);
            }
            emitter.complete();
        }
    }

    private void emit(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException ex) {
            throw new IllegalStateException("SSE 连接已断开", ex);
        }
    }
}
