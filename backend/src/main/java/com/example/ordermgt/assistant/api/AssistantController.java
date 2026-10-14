package com.example.ordermgt.assistant.api;

import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.security.CurrentCustomer;
import com.example.ordermgt.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final ChatSender chatSender;

    /**
     * Conversation-aware chat. Pass an existing {@code conversationId} to continue it, or omit it
     * to start a new conversation -- the response always carries back the id to use next.
     *
     * <p>A retried request with the same {@code Idempotency-Key} replays the stored answer instead
     * of paying for the model call a second time.
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(
            @CurrentCustomer Long customerId,
            @RequestParam(required = false) Long conversationId,
            @RequestHeader(value = IdempotencyGuard.KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(
                chatSender.send(customerId, conversationId, idempotencyKey, request.message()));
    }
}
