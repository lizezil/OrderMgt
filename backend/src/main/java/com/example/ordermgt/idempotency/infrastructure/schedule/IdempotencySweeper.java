package com.example.ordermgt.idempotency.infrastructure.schedule;

import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reopens keys that were left behind by a crash.
 *
 * <p>{@code IdempotencyGuard} is deliberately not transactional (the guarded action may be a model
 * call), so a process that dies between the action committing and the record being marked COMPLETED
 * leaves the key IN_PROGRESS. Until it expires, a retry with that key is refused with a 409 -- the
 * client is stuck on work that already succeeded. This sweep fails those stale keys once their
 * retention window has passed, and FAILED is exactly the state that allows a retry with the same
 * key.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class IdempotencySweeper {

    private final IdempotencyRepository idempotencyRepository;

    @Scheduled(
            fixedDelayString = "${idempotency.sweep-interval-ms:60000}",
            initialDelayString = "${idempotency.sweep-interval-ms:60000}")
    public void expireStaleInProgress() {
        try {
            int failed = idempotencyRepository.expireStaleInProgress(Instant.now());
            if (failed > 0) {
                log.info("failed {} stale in-progress idempotency key(s)", failed);
            }
        } catch (RuntimeException ex) {
            log.warn("idempotency sweep failed: {}", ex.getMessage());
        }
    }
}
