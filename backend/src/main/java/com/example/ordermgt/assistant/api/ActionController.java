package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.ActionResolution;
import com.example.ordermgt.assistant.application.ReinitiatedAction;
import com.example.ordermgt.security.CurrentCustomer;
import com.example.ordermgt.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The confirmation endpoints. This is the only door through which a mutating tool runs.
 *
 * <p>A token that belongs to somebody else is answered with FORBIDDEN; a token nobody holds with
 * NOT_FOUND; an expired one with GONE.
 *
 * <p>Resolving a card moves its conversation out of WAITING_APPROVAL, so each endpoint pushes the
 * conversation's new state to the customer's status stream (ADR-0014): the sidebar badge on every
 * open tab updates without a refresh. Reinitiating pushes the same way -- the conversation has a
 * live card waiting again.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/assistant/actions")
public class ActionController {

    private final ActionConfirmationService actionConfirmationService;
    private final ConversationEventPublisher events;

    @PostMapping("/{token}/confirm")
    public ApiResponse<ActionResolution> confirm(
            @CurrentCustomer Long customerId, @PathVariable String token) {
        return ApiResponse.ok(
                publish(customerId, actionConfirmationService.confirm(customerId, token)));
    }

    @PostMapping("/{token}/reject")
    public ApiResponse<ActionResolution> reject(
            @CurrentCustomer Long customerId, @PathVariable String token) {
        return ApiResponse.ok(
                publish(customerId, actionConfirmationService.reject(customerId, token)));
    }

    /**
     * Mints a fresh card from an expired one (US-1.12): same proposal, new token, new TTL -- and no
     * execution. Reuses the same ownership check as confirm/reject, so a stranger's token reads as
     * absent here too.
     */
    @PostMapping("/{token}/reinitiate")
    public ApiResponse<ReinitiatedAction> reinitiate(
            @CurrentCustomer Long customerId, @PathVariable String token) {
        ReinitiatedAction reinitiated = actionConfirmationService.reinitiate(customerId, token);
        events.stateChanged(customerId, reinitiated.conversationId());
        return ApiResponse.ok(reinitiated);
    }

    private ActionResolution publish(Long customerId, ActionResolution resolution) {
        events.stateChanged(customerId, resolution.conversationId());
        return resolution;
    }
}
