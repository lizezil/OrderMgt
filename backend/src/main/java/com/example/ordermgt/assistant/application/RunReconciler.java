package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.RunRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup reconciliation: the instant a single instance comes up there cannot be a live Run, so any
 * row the database still shows as RUNNING was abandoned by a process that died. Until it is closed
 * the state column lies -- the sidebar would read 生成中 forever -- so it is closed here, with the end
 * time it never got (ADR-0011).
 *
 * <p>An {@link ApplicationRunner} on purpose: it fires once, after the context is up, and never
 * again. It is deliberately *not* a {@code @Scheduled} job -- a timer would re-scan the table for
 * the lifetime of the process to repair something that only ever goes wrong at startup.
 *
 * <p>A failure is logged, not thrown: a transient database error must not keep the application from
 * starting, and the next restart reconciles what this one missed. Same temperament as {@link
 * com.example.ordermgt.assistant.infrastructure.schedule.PendingActionSweeper}.
 *
 * <p>Interrupting a run is not "resuming half of it": the reply was never persisted and will never
 * be (ADR-0009). The customer message behind it is already in history, so the interrupted turn
 * reads as a question with no answer, and the way forward is a brand new Run -- see the retry entry
 * in the SPA.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunReconciler implements ApplicationRunner {

    /** Recorded on a run the previous process never finished, so the column explains itself. */
    public static final String INTERRUPTED_REASON =
            "interrupted: the process that owned it is gone";

    private final RunRepository runRepository;

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    /**
     * Public so the boot step can also be driven directly -- a test simulating a restart calls this
     * rather than standing the whole context up again.
     *
     * @return how many residual runs were interrupted
     */
    public int reconcile() {
        try {
            int interrupted = runRepository.interruptRunning(INTERRUPTED_REASON, Instant.now());
            if (interrupted > 0) {
                log.info("interrupted {} run(s) left RUNNING by a previous process", interrupted);
            }
            return interrupted;
        } catch (RuntimeException ex) {
            log.warn("startup run reconciliation failed: {}", ex.getMessage());
            return 0;
        }
    }
}
