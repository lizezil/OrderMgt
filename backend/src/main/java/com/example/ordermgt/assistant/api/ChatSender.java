package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The idempotent send-message path shared by both chat entry points ({@code /assistant/chat} and
 * {@code /conversations/{id}/messages}): one operation name, one request fingerprint, one replay
 * semantic -- stated once so the two controllers cannot drift apart.
 *
 * <p>It is also where the blocking transport meets ADR-0012. A turn already in flight on this
 * conversation makes a blocking send impossible, and rather than wait it answers {@code CONFLICT}
 * (409): this path carries an {@code Idempotency-Key} and promises "run now or not at all", and
 * making it wait would put a long block inside a claim that already committed. The ownership check
 * comes first, before the busy test, so a stranger cannot learn from a 409 that a conversation
 * exists and is busy -- for them it is still a 403/404 and indistinguishable from a quiet one.
 */
@RequiredArgsConstructor
@Component
public class ChatSender {

    static final String OPERATION = "SEND_MESSAGE";

    private final AssistantService assistantService;
    private final IdempotencyGuard idempotencyGuard;
    private final AssistantApiMapper mapper;
    private final ConversationService conversationService;
    private final ConversationRunQueue runQueue;

    public ChatResponse send(
            Long customerId, Long conversationId, String idempotencyKey, String message) {
        return idempotencyGuard.execute(
                customerId,
                OPERATION,
                idempotencyKey,
                IdempotencyGuard.hashOf(String.valueOf(conversationId), message),
                () -> chat(customerId, conversationId, message),
                ChatResponse.class);
    }

    /**
     * Runs the blocked turn under the conversation's single-run invariant. A {@code null}
     * conversationId starts a new one, which cannot be busy because it did not exist a moment ago.
     */
    private ChatResponse chat(Long customerId, Long conversationId, String message) {
        if (conversationId == null) {
            return mapper.toResponse(assistantService.chat(customerId, null, message));
        }
        conversationService.getOwned(customerId, conversationId);
        return runQueue.runBlocking(
                conversationId,
                () ->
                        mapper.toResponse(
                                assistantService.chat(customerId, conversationId, message)));
    }
}
