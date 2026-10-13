package com.example.ordermgt.assistant;

import java.time.Duration;

/**
 * How long an AI call may take and how often it is retried -- the one home for the numbers that the
 * transports would otherwise each hard-code.
 *
 * <p>Two transports are covered: the blocking {@code RestClient} call (HTTP timeouts, see {@code
 * AiHttpClientConfig}) and the streaming {@code WebClient} call (a hard wall clock, see {@code
 * SpringAiAdapter}). The retry schedule is the same shape on both, mirroring {@code
 * spring.ai.retry} in {@code application.yml}: 3 attempts, 1s x2, capped at 8s, no jitter.
 */
public final class AiCallPolicy {

    /** A stalled stream cannot pin a worker thread forever (see ADR-0009 for the blast radius). */
    public static final long STREAM_TIMEOUT_MS = 120_000L;

    public static final Duration STREAM_TIMEOUT = Duration.ofMillis(STREAM_TIMEOUT_MS);

    /** First-token replay attempts for a stream; once a token is out a retry would duplicate it. */
    public static final int STREAM_RETRY_ATTEMPTS = 3;

    public static final Duration RETRY_INITIAL_BACKOFF = Duration.ofSeconds(1);

    public static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(8);

    /** Blocking chat completion: 5s to connect, 60s to read a first response. */
    public static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    public static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(60);

    private AiCallPolicy() {}
}
