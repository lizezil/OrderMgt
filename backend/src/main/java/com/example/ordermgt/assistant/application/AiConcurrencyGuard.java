package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.AiCallPolicy;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The global ceiling on model calls in flight -- the second half of rate limiting. The per-customer
 * window stops one customer flooding; this stops everyone flooding at once, whichever mix of
 * blocking and streaming they arrived on.
 *
 * <p>A full guard refuses immediately rather than queueing: a wait queue would let a burst hold
 * every worker hostage, and the caller can decide for itself whether the {@code Retry-After} is
 * worth waiting. The permit is held for the whole call -- a stream keeps it from before the first
 * token until the stream ends, because that is when the model connection is actually occupied.
 */
@Component
public class AiConcurrencyGuard {

    private final Semaphore permits;

    public AiConcurrencyGuard(@Value("${assistant.ai.max-concurrent:8}") int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent, true);
    }

    /** Runs the AI call holding one permit; released on every exit path, failure included. */
    public <T> T withPermit(Supplier<T> call) {
        if (!permits.tryAcquire()) {
            throw new BusinessException(
                    ErrorCode.RATE_LIMITED,
                    "同时进行的AI请求太多，请稍后再试",
                    AiCallPolicy.BUSY_RETRY_AFTER_SECONDS);
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }
}
