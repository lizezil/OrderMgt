package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunTest {

    @Test
    void aRunStartsRunningWithNoEnd() {
        Run run = Run.start(7L);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.endedAt()).isNull();
        assertThat(run.error()).isNull();
        assertThat(run.conversationId()).isEqualTo(7L);
        assertThat(run.isRunning()).isTrue();
    }

    @Test
    void aRunNeedsAConversation() {
        assertThatThrownBy(() -> Run.start(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finishingMovesRunningToTerminalWithAnEndTime() {
        Instant endedAt = Instant.now().plusSeconds(3);
        Run run = Run.start(7L);

        Run finished = run.finish(RunStatus.COMPLETED, null, endedAt);

        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.endedAt()).isEqualTo(endedAt);
        assertThat(finished.isRunning()).isFalse();
        // finish returns the new row state; the RUNNING instance it came from is untouched.
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aRunCannotFinishIntoRunning() {
        assertThatThrownBy(() -> Run.start(7L).finish(RunStatus.RUNNING, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFinishedRunCannotFinishAgain() {
        Run finished = Run.start(7L).finish(RunStatus.COMPLETED, null, Instant.now());

        assertThatThrownBy(() -> finished.finish(RunStatus.FAILED, "x", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFailureReasonIsTruncatedToTheColumn() {
        Run finished = Run.start(7L).finish(RunStatus.FAILED, "e".repeat(2000), Instant.now());

        assertThat(finished.error()).hasSize(Run.ERROR_MAX);
    }
}
