package com.example.ordermgt.idempotency.infrastructure.schedule;

import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes retention real by deleting expired keys.
 *
 * <p>{@code IdempotencyGuard} is deliberately not transactional (the guarded action may be a model
 * call), so a process that dies between the action committing and the record being marked COMPLETED
 * leaves the key IN_PROGRESS. Until expiry, a retry with that key is refused with a 409 -- the
 * client is stuck on work that already succeeded. Once the retention window has passed, this sweep
 * deletes the row entirely: the key becomes claimable again, replays stop, and the table stays
 * bounded. Deletion (rather than flipping stale rows to FAILED) also means there is no window in
 * which a half-forgotten key lingers forever.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class IdempotencySweeper {

    private final IdempotencyRepository idempotencyRepository;

    @Scheduled(
            fixedDelayString = "${idempotency.sweep-interval-ms:60000}",
            initialDelayString = "${idempotency.sweep-interval-ms:60000}")
    public void deleteExpired() {
        try {
            int deleted = idempotencyRepository.deleteExpired(Instant.now());
            if (deleted > 0) {
                log.info("deleted {} expired idempotency key(s)", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("idempotency sweep failed: {}", ex.getMessage());
        }
    }
}
