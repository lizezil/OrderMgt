package com.example.ordermgt.assistant.infrastructure.schedule;

import com.example.ordermgt.assistant.domain.PendingActionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flips overdue PENDING actions to EXPIRED so the status column tells the truth even when nobody
 * comes back to the card. Confirm/reject also check the deadline on read, so a failed sweep can
 * never let an expired action execute.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PendingActionSweeper {

    private final PendingActionRepository pendingActionRepository;

    @Scheduled(
            fixedDelayString = "${assistant.action.sweep-interval-ms:60000}",
            initialDelayString = "${assistant.action.sweep-interval-ms:60000}")
    @Transactional
    public void expireOverdue() {
        try {
            int expired = pendingActionRepository.expireOverdue(Instant.now());
            if (expired > 0) {
                log.info("expired {} overdue pending action(s)", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("pending action sweep failed: {}", ex.getMessage());
        }
    }
}
