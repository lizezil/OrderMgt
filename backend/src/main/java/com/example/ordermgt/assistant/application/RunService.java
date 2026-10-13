package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The explicit stop (ADR-0012). A customer stopping a turn is a deliberate action, never a
 * side-effect of a dropped connection -- that one keeps the turn running (ADR-0009). Stopping trips
 * the run's cancellation signal, which the streaming model call observes and uses to abort the
 * upstream stream, so the tokens past the stop are not paid for.
 *
 * <p>Idempotent: a Run that is already terminal is a no-op, not an error, since a second tab (or a
 * double click) may legitimately stop the same run twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    private final RunRepository runRepository;
    private final RunRegistry runRegistry;

    /**
     * Stops {@code runId}, which must belong to {@code conversationId}. The caller has already
     * checked that the conversation belongs to the customer; a run that is not in it is reported as
     * absent, the same as one that never existed.
     */
    public void stop(Long conversationId, Long runId) {
        Run run = require(conversationId, runId);
        if (!run.isRunning()) {
            // Already finished: nothing to stop, and saying so with an error would punish the
            // second of two legitimate stops.
            return;
        }
        // A RUNNING row with no handle here is a run this process is not executing (another
        // instance, or the brief startup window before reconciliation). There is nothing to abort,
        // and pretending otherwise would lie; the startup reconciliation closes such rows.
        if (!runRegistry.cancel(runId)) {
            log.info("stop requested for run {} but this process is not running it", runId);
        }
    }

    /**
     * The run, checked to belong to the conversation. A run in another conversation is reported as
     * absent, the same as one that never existed -- the caller has already checked that the
     * conversation itself belongs to the customer.
     */
    public Run require(Long conversationId, Long runId) {
        Run run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "run not found: " + runId));
        if (!run.conversationId().equals(conversationId)) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "run " + runId + " does not belong to conversation " + conversationId);
        }
        return run;
    }
}
