package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The idempotent send-message path shared by both chat entry points ({@code /assistant/chat} and
 * {@code /conversations/{id}/messages}): one operation name, one request fingerprint, one replay
 * semantic -- stated once so the two controllers cannot drift apart.
 */
@RequiredArgsConstructor
@Component
public class ChatSender {

    static final String OPERATION = "SEND_MESSAGE";

    private final AssistantService assistantService;
    private final IdempotencyGuard idempotencyGuard;
    private final AssistantApiMapper mapper;

    public ChatResponse send(
            Long customerId, Long conversationId, String idempotencyKey, String message) {
        return idempotencyGuard.execute(
                customerId,
                OPERATION,
                idempotencyKey,
                IdempotencyGuard.hashOf(String.valueOf(conversationId), message),
                () -> mapper.toResponse(assistantService.chat(customerId, conversationId, message)),
                ChatResponse.class);
    }
}
