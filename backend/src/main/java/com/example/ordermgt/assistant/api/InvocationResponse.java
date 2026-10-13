package com.example.ordermgt.assistant.api;

import java.time.Instant;

/**
 * One audited model call as operations sees it: which model answered, how long it took, whether it
 * landed (OK / ERROR / TIMEOUT) and when. Token spend and the raw error stay internal.
 */
public record InvocationResponse(
        String model, Integer latencyMs, String outcome, Instant createdAt) {}
