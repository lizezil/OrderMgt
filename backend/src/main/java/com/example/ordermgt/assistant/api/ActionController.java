package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.ActionResolution;
import com.example.ordermgt.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The confirmation endpoints. This is the only door through which a mutating tool runs.
 *
 * <p>A token that belongs to somebody else is answered with FORBIDDEN; a token nobody holds with
 * NOT_FOUND; an expired one with GONE.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/assistant/actions")
public class ActionController {

    private final ActionConfirmationService actionConfirmationService;

    @PostMapping("/{token}/confirm")
    public ApiResponse<ActionResolution> confirm(
            @RequestHeader("X-Customer-Id") Long customerId, @PathVariable String token) {
        return ApiResponse.ok(actionConfirmationService.confirm(customerId, token));
    }

    @PostMapping("/{token}/reject")
    public ApiResponse<ActionResolution> reject(
            @RequestHeader("X-Customer-Id") Long customerId, @PathVariable String token) {
        return ApiResponse.ok(actionConfirmationService.reject(customerId, token));
    }
}
