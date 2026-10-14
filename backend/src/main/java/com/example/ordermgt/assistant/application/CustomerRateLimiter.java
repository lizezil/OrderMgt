package com.example.ordermgt.assistant.application;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-customer admission control for the AI entry points: at most N sends per sliding minute,
 * counted across both transports (blocking chat and streaming). One customer's burst must not be
 * able to burn the whole model budget, and the refusal is honest 429 with a {@code Retry-After}
 * that counts down to the moment the oldest request slides out of the window.
 *
 * <p>In memory, single instance (per the demo-grade positioning): a restart forgets the windows,
 * which only ever makes the limiter more generous, never wrongly strict. The key space is the
 * configured customer set (the PIN map), so the windows map is bounded by deployment config, not by
 * traffic.
 */
@Component
public class CustomerRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int limit;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    /** The Spring injection point -- the other constructor is test-only, so one must be marked. */
    @Autowired
    public CustomerRateLimiter(@Value("${assistant.ai.rate-limit:6}") int limitPerMinute) {
        this(limitPerMinute, System::currentTimeMillis);
    }

    /** The clock is injectable so the window slide is assertable in contract tests too. */
    public CustomerRateLimiter(int limitPerMinute, LongSupplier clock) {
        this.limit = limitPerMinute;
        this.clock = clock;
    }

    /** A limiter that never refuses -- for suites whose subject is something else. */
    public static CustomerRateLimiter unlimited() {
        return new CustomerRateLimiter(Integer.MAX_VALUE, System::currentTimeMillis);
    }

    /**
     * Records one admission attempt. Inside the limit it returns normally and the request proceeds;
     * past it, throws {@code RATE_LIMITED} whose {@code Retry-After} is the seconds until the
     * oldest in-window request expires (at least 1, so a client never busy-loops on zero).
     */
    public void admit(Long customerId) {
        long now = clock.getAsLong();
        Deque<Long> window = windows.computeIfAbsent(customerId, id -> new ArrayDeque<>());
        synchronized (window) {
            long cutoff = now - WINDOW_MILLIS;
            while (!window.isEmpty() && window.peekFirst() <= cutoff) {
                window.pollFirst();
            }
            if (window.size() < limit) {
                window.addLast(now);
                return;
            }
            long waitMillis = window.peekFirst() + WINDOW_MILLIS - now;
            long retryAfter = Math.max(1, (waitMillis + 999) / 1000);
            throw new BusinessException(
                    ErrorCode.RATE_LIMITED, "发送太频繁了，请等 " + retryAfter + " 秒后再试", (int) retryAfter);
        }
    }
}
