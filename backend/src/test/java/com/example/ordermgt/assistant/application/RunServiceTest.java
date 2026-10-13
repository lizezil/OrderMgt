package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Business rule: 停止是显式动作且幂等（ADR-0012）——只对 RUNNING 生效，trip 掉那条 Run 的取消信号；对已终态的 Run 再停一次无副作用；不属于这条会话的
 * Run 与不存在的 Run 一样报 404。
 */
@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    private static final Long CONVERSATION = 7L;
    private static final Long RUN = 42L;

    @Mock private RunRepository runRepository;
    @Mock private RunRegistry runRegistry;

    private RunService service;

    @BeforeEach
    void setUp() {
        service = new RunService(runRepository, runRegistry);
    }

    @Test
    void stoppingARunningRunTripsItsCancellation() {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

        service.stop(CONVERSATION, RUN);

        verify(runRegistry).cancel(RUN);
    }

    @Test
    void stoppingAFinishedRunIsANoOp() {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        service.stop(CONVERSATION, RUN);

        verify(runRegistry, never()).cancel(RUN);
    }

    @Test
    void stoppingAnUnknownRunIsNotFound() {
        when(runRepository.findById(RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stop(CONVERSATION, RUN))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(runRegistry, never()).cancel(RUN);
    }

    @Test
    void aRunFromAnotherConversationIsNotFound() {
        when(runRepository.findById(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING, 99L)));

        assertThatThrownBy(() -> service.stop(CONVERSATION, RUN))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(runRegistry, never()).cancel(RUN);
    }

    private static Run run(RunStatus status) {
        return run(status, CONVERSATION);
    }

    private static Run run(RunStatus status, Long conversationId) {
        Instant now = Instant.now();
        return Run.rehydrate(RUN, conversationId, status, null, now, now);
    }
}
