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
    private final ConversationEventPublisher events;

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
     *
     * <p>The turn changed the conversation's state (a new message, and possibly a confirmation
     * card), so the customer's status stream is told once it lands -- the same push the streaming
     * transport does, so a badge on another tab does not depend on which transport sent.
     */
    private ChatResponse chat(Long customerId, Long conversationId, String message) {
        if (conversationId == null) {
            ChatResponse response =
                    mapper.toResponse(assistantService.chat(customerId, null, message));
            events.stateChanged(customerId, response.conversationId());
            return response;
        }
        conversationService.getOwned(customerId, conversationId);
        ChatResponse response =
                runQueue.runBlocking(
                        conversationId,
                        () ->
                                mapper.toResponse(
                                        assistantService.chat(
                                                customerId, conversationId, message)));
        events.stateChanged(customerId, conversationId);
        return response;
    }
}
