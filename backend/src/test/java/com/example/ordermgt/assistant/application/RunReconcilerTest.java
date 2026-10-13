package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.RunRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Business rule: 重启即一次对账。单实例启动那一刻不可能有活 Run，所以库里残留的 RUNNING 一定是
 * 上一个进程留下的，必须在启动时一次性收尾——否则侧栏会永远显示「生成中」。
 */
@ExtendWith(MockitoExtension.class)
class RunReconcilerTest {

    @Mock private RunRepository runRepository;

    private RunReconciler reconciler() {
        return new RunReconciler(runRepository);
    }

    /** 对账把所有残留 RUNNING 一并收尾，并带回数量，且把原因与结束时刻一起写下去。 */
    @Test
    void interruptsEveryResidualRunningRunWithAReasonAndAnEndTime() {
        when(runRepository.interruptRunning(any(), any())).thenReturn(2);

        assertThat(reconciler().reconcile()).isEqualTo(2);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository).interruptRunning(any(), at.capture());
        assertThat(at.getValue()).isNotNull();
    }

    @Test
    void recordsWhyTheRunWasInterrupted() {
        when(runRepository.interruptRunning(any(), any())).thenReturn(1);

        reconciler().reconcile();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(runRepository).interruptRunning(reason.capture(), any());
        assertThat(reason.getValue()).isEqualTo(RunReconciler.INTERRUPTED_REASON);
    }

    /** 对账失败不能把应用拦在启动之外：下一次重启会再对一次。 */
    @Test
    void aFailedReconciliationIsSwallowedSoStartupStillSucceeds() {
        when(runRepository.interruptRunning(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(reconciler().reconcile()).isZero();
    }

    /**
     * 触发方式被 ADR-0011 钉死：启动时一次性的 {@link org.springframework.boot.ApplicationRunner}， **不是**
     * {@code @Scheduled}——定时器会为了一个只在启动时出现的问题，在整个进程生命周期里反复扫表。
     */
    @Test
    void reconciliationIsAStartupHookNotAScheduledJob() {
        when(runRepository.interruptRunning(any(), any())).thenReturn(0);

        reconciler().run(mock(ApplicationArguments.class));

        verify(runRepository).interruptRunning(any(), any());
        assertThat(RunReconciler.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class));
    }
}
