package com.example.ordermgt.shared.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/**
 * Business error codes. HTTP status is carried here so the exception handler never has to guess.
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "please sign in to continue"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "resource not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "access to this resource is forbidden"),
    CONFLICT(HttpStatus.CONFLICT, "conflicting state"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "idempotency key conflict"),
    RESULT_UNKNOWN(
            HttpStatus.CONFLICT,
            "the operation ran but its result could not be recorded; verify the state before retrying"),
    // A full queue is a capacity fact, not a state conflict: retrying the same request later is the
    // right advice, which is what 429 says and 409 does not.
    QUEUE_FULL(HttpStatus.TOO_MANY_REQUESTS, "this conversation's queue is full"),
    RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "too many requests; please wait for the Retry-After interval and try again"),
    ACTION_EXPIRED(HttpStatus.GONE, "the pending action has expired"),
    UPSTREAM_AI_ERROR(HttpStatus.BAD_GATEWAY, "AI upstream call failed"),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "the service is busy; please retry shortly"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");

    private final HttpStatus status;
    private final String defaultMessage;
}
