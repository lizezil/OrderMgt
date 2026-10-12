package com.example.ordermgt.assistant.infrastructure.schedule;

import com.example.ordermgt.assistant.domain.PendingActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Flips overdue PENDING actions to EXPIRED so the status column tells the truth even when
 * nobody comes back to the card. Confirm/reject also check the deadline on read, so a
 * failed sweep can never let an expired action execute.
 */
@Component
public class PendingActionSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingActionSweeper.class);

    private final PendingActionRepository pendingActionRepository;

    public PendingActionSweeper(PendingActionRepository pendingActionRepository) {
        this.pendingActionRepository = pendingActionRepository;
    }

    @Scheduled(fixedDelayString = "${assistant.action.sweep-interval-ms:60000}",
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
