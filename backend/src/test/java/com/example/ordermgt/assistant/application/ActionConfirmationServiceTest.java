package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.ToolCard;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionConfirmationServiceTest {

    private static final Long OWNER = 1001L;
    private static final Long STRANGER = 2002L;

    @Mock
    private PendingActionRepository repository;

    private final AtomicInteger executions = new AtomicInteger();
    private ActionConfirmationService service;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry(List.of(
                ToolSpec.mutating("createOrder", "创建订单", "{}",
                        new ToolCard("创建订单", args -> "创建订单", args -> List.of()),
                        (customerId, args) -> {
                            executions.incrementAndGet();
                            return ToolResult.of("{\"orderNo\":\"ORD-9\"}", "ORD-9");
                        }),
                ToolSpec.readonly("queryMyOrders", "查询订单", "{}",
                        (customerId, args) -> ToolResult.of("[]"))));
        service = new ActionConfirmationService(repository, registry);
    }

    @Test
    void confirmingRunsTheHandlerOnceAndRecordsWhatItProduced() {
        PendingAction action = pending();
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        ActionResolution resolution = service.confirm(OWNER, action.getToken());

        assertThat(resolution.status()).isEqualTo(PendingActionStatus.CONFIRMED.name());
        assertThat(resolution.replayed()).isFalse();
        assertThat(resolution.resultJson()).contains("ORD-9");
        assertThat(executions).hasValue(1);
        assertThat(action.getResultRef()).isEqualTo("ORD-9");
        verify(repository).save(action);
    }

    @Test
    void confirmingAgainReplaysInsteadOfExecutingAgain() {
        PendingAction action = pending();
        action.resolve(PendingActionStatus.CONFIRMED, "ORD-9", Instant.now());
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        ActionResolution resolution = service.confirm(OWNER, action.getToken());

        assertThat(resolution.replayed()).isTrue();
        assertThat(resolution.status()).isEqualTo(PendingActionStatus.CONFIRMED.name());
        assertThat(executions).hasValue(0);
    }

    @Test
    void aStrangerCannotConfirmSomeoneElsesAction() {
        PendingAction action = pending();
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.confirm(STRANGER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(executions).hasValue(0);
    }

    @Test
    void anExpiredActionIsNeverExecuted() {
        PendingAction action = PendingAction.propose(OWNER, 7L, "createOrder", "{}", "创建订单",
                Duration.ofMinutes(-1));
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.confirm(OWNER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACTION_EXPIRED);
        assertThat(executions).hasValue(0);
    }

    @Test
    void anUnknownTokenIsNotFound() {
        when(repository.lockByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(OWNER, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aReadonlyToolCanNeverBeConfirmedIntoExecution() {
        PendingAction action = PendingAction.rehydrate(1L, "t", OWNER, 7L, null, "queryMyOrders",
                "{}", "查询订单", PendingActionStatus.PENDING, Instant.now().plusSeconds(60),
                null, null, Instant.now());
        when(repository.lockByToken("t")).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.confirm(OWNER, "t"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void rejectingRecordsTheDecisionWithoutExecuting() {
        PendingAction action = pending();
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        ActionResolution resolution = service.reject(OWNER, action.getToken());

        assertThat(resolution.status()).isEqualTo(PendingActionStatus.REJECTED.name());
        assertThat(resolution.resultJson()).isNull();
        assertThat(executions).hasValue(0);
    }

    @Test
    void anAlreadyConfirmedActionCannotBeRejected() {
        PendingAction action = pending();
        action.resolve(PendingActionStatus.CONFIRMED, "ORD-9", Instant.now());
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.reject(OWNER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repository, never()).save(any());
    }

    private PendingAction pending() {
        return PendingAction.propose(OWNER, 7L, "createOrder",
                "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}", "创建订单：GIZMO-03 × 3",
                Duration.ofMinutes(15));
    }
}
