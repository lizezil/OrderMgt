package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.ToolCard;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionConfirmationServiceTest {

    private static final Long OWNER = 1001L;
    private static final Long STRANGER = 2002L;
    private static final Long CONVERSATION = 7L;
    private static final String ARGUMENTS = "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}";
    private static final String SUMMARY = "Place order: GIZMO-03 × 3";

    @Mock private PendingActionRepository repository;

    @Mock private ConversationService conversationService;

    private final AtomicInteger executions = new AtomicInteger();
    private ActionConfirmationService service;

    @BeforeEach
    void setUp() {
        ToolRegistry registry =
                new ToolRegistry(
                        List.of(
                                ToolSpec.mutating(
                                        "createOrder",
                                        "Place order",
                                        "{}",
                                        new ToolCard(
                                                "Place Order",
                                                args -> "Place order",
                                                args -> List.of()),
                                        (customerId, args) -> {
                                            executions.incrementAndGet();
                                            return ToolResult.of(
                                                    "{\"orderNo\":\"ORD-9\"}", "ORD-9");
                                        }),
                                ToolSpec.readonly(
                                        "queryMyOrders",
                                        "Query orders",
                                        "{}",
                                        payload -> "你名下还没有订单。",
                                        (customerId, args) -> ToolResult.of("[]"))));
        service =
                new ActionConfirmationService(
                        repository,
                        registry,
                        conversationService,
                        new ActionCardFactory(registry, new ObjectMapper()),
                        15);
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
        PendingAction action =
                PendingAction.propose(
                        OWNER, 7L, "createOrder", "{}", "Place order", Duration.ofMinutes(-1));
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
        PendingAction action =
                PendingAction.rehydrate(
                        1L,
                        "t",
                        OWNER,
                        7L,
                        null,
                        "queryMyOrders",
                        "{}",
                        "Query orders",
                        PendingActionStatus.PENDING,
                        Instant.now().plusSeconds(60),
                        null,
                        null,
                        Instant.now());
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

    @Test
    void anExpiredCardCanBeReinitiatedIntoAFreshOne() {
        PendingAction action =
                PendingAction.rehydrate(
                        1L,
                        "old-token",
                        OWNER,
                        CONVERSATION,
                        null,
                        "createOrder",
                        ARGUMENTS,
                        SUMMARY,
                        PendingActionStatus.EXPIRED,
                        Instant.now().minusSeconds(60),
                        null,
                        null,
                        Instant.now().minusSeconds(3600));
        when(repository.lockByToken("old-token")).thenReturn(Optional.of(action));
        stubSaveAssigningId();
        when(conversationService.appendAssistantMessage(CONVERSATION, "Place order", 99L))
                .thenReturn(
                        new Message(
                                77L,
                                CONVERSATION,
                                Role.ASSISTANT,
                                "Place order",
                                99L,
                                Instant.now()));

        ReinitiatedAction reinitiated = service.reinitiate(OWNER, "old-token");

        ArgumentCaptor<PendingAction> saved = ArgumentCaptor.forClass(PendingAction.class);
        verify(repository).save(saved.capture());
        PendingAction renewed = saved.getValue();
        assertThat(renewed.getToolName()).isEqualTo("createOrder");
        assertThat(renewed.getArgumentsJson()).isEqualTo(ARGUMENTS);
        assertThat(renewed.getSummary()).isEqualTo(SUMMARY);
        assertThat(renewed.getToken()).isNotEqualTo("old-token");
        assertThat(renewed.isDeadAt(Instant.now())).isFalse();

        ActionCard card = reinitiated.card();
        assertThat(card.token()).isEqualTo(renewed.getToken());
        assertThat(card.toolName()).isEqualTo("createOrder");
        assertThat(card.argumentsJson()).isEqualTo(ARGUMENTS);
        assertThat(card.status()).isEqualTo(PendingActionStatus.PENDING.name());
        assertThat(card.expiresAt()).isAfter(Instant.now());
        assertThat(card.confirmEndpoint())
                .isEqualTo("/api/assistant/actions/" + renewed.getToken() + "/confirm");
        assertThat(reinitiated.conversationId()).isEqualTo(CONVERSATION);
        assertThat(executions).hasValue(0);

        verify(conversationService).appendAssistantMessage(CONVERSATION, "Place order", 99L);
        verify(conversationService).linkActionToMessage(any(PendingAction.class), eq(77L));
        // 原卡片原样不动：旧 token 依旧指向一张死卡。
        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.EXPIRED);
        assertThat(action.getToken()).isEqualTo("old-token");
    }

    @Test
    void aPendingCardPastItsDeadlineCanAlsoBeReinitiated() {
        PendingAction action =
                PendingAction.propose(
                        OWNER,
                        CONVERSATION,
                        "createOrder",
                        ARGUMENTS,
                        SUMMARY,
                        Duration.ofMinutes(-1));
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));
        stubSaveAssigningId();
        when(conversationService.appendAssistantMessage(CONVERSATION, "Place order", 99L))
                .thenReturn(
                        new Message(
                                77L,
                                CONVERSATION,
                                Role.ASSISTANT,
                                "Place order",
                                99L,
                                Instant.now()));

        ReinitiatedAction reinitiated = service.reinitiate(OWNER, action.getToken());

        assertThat(reinitiated.card().status()).isEqualTo(PendingActionStatus.PENDING.name());
        assertThat(reinitiated.card().expiresAt()).isAfter(Instant.now());
        assertThat(executions).hasValue(0);
        // 清扫器没跑、状态还是 PENDING 也不碍事：死卡的判定看的是 deadline。
        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.PENDING);
    }

    @Test
    void aStillLiveCardCannotBeReinitiated() {
        PendingAction action = pending();
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.reinitiate(OWNER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repository, never()).save(any());
        verify(conversationService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void aRejectedCardCannotBeReinitiated() {
        PendingAction action = pending();
        action.resolve(PendingActionStatus.REJECTED, null, Instant.now());
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.reinitiate(OWNER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repository, never()).save(any());
    }

    @Test
    void aStrangerCannotReinitiateSomeoneElsesCard() {
        PendingAction action = pending();
        when(repository.lockByToken(action.getToken())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.reinitiate(STRANGER, action.getToken()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(executions).hasValue(0);
        verify(repository, never()).save(any());
    }

    private void stubSaveAssigningId() {
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PendingAction saved = invocation.getArgument(0);
                            saved.assignId(99L);
                            return saved;
                        });
    }

    private PendingAction pending() {
        return PendingAction.propose(
                OWNER,
                7L,
                "createOrder",
                "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}",
                "Place order: GIZMO-03 × 3",
                Duration.ofMinutes(15));
    }
}
