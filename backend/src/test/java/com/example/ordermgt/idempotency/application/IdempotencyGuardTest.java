package com.example.ordermgt.idempotency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.order.api.CreateOrderRequest;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    private static final String OPERATION = "CREATE_ORDER";
    private static final String KEY = "key-1";
    private static final String HASH = "hash-1";

    @Mock private IdempotencyRepository repository;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(repository, new ObjectMapper(), 24);
    }

    @Test
    void withoutAKeyTheActionSimplyRuns() {
        Map<String, Object> result =
                guard.execute(
                        1L, OPERATION, "  ", HASH, () -> Map.of("orderNo", "ORD-1"), Map.class);

        assertThat(result).containsEntry("orderNo", "ORD-1");
        verify(repository, never()).tryClaim(any());
    }

    @Test
    void claimingTheKeyMeansTheActionRunsAndTheResponseIsStored() {
        IdempotencyRecord claim = record(9L, IdempotencyStatus.IN_PROGRESS, null, HASH);
        when(repository.tryClaim(any())).thenReturn(Optional.of(claim));

        Map<String, Object> result =
                guard.execute(
                        1L, OPERATION, KEY, HASH, () -> Map.of("orderNo", "ORD-1"), Map.class);

        assertThat(result).containsEntry("orderNo", "ORD-1");
        verify(repository).complete(eq(9L), anyString());
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void aCompletedKeyReplaysTheStoredResponseWithoutRunningTheAction() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(
                        Optional.of(
                                record(
                                        9L,
                                        IdempotencyStatus.COMPLETED,
                                        "{\"orderNo\":\"ORD-1\"}",
                                        HASH)));

        Map<String, Object> replayed =
                guard.execute(
                        1L,
                        OPERATION,
                        KEY,
                        HASH,
                        () -> {
                            throw new AssertionError("the action must not run twice");
                        },
                        Map.class);

        assertThat(replayed).containsEntry("orderNo", "ORD-1");
        verify(repository, never()).complete(anyLong(), anyString());
    }

    @Test
    void theSameKeyWithADifferentBodyIsAReusedKeyNotARetry() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(
                        Optional.of(
                                record(9L, IdempotencyStatus.COMPLETED, "{}", "a-different-hash")));

        assertThatThrownBy(() -> guard.execute(1L, OPERATION, KEY, HASH, () -> Map.of(), Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aConcurrentDuplicateIsRefusedWhileTheFirstIsStillRunning() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.IN_PROGRESS, null, HASH)));

        assertThatThrownBy(() -> guard.execute(1L, OPERATION, KEY, HASH, () -> Map.of(), Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aFailedAttemptCanBeRetriedWithTheSameKey() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.FAILED, null, HASH)));
        when(repository.reclaim(anyLong(), any(Instant.class))).thenReturn(true);

        Map<String, Object> result =
                guard.execute(
                        1L, OPERATION, KEY, HASH, () -> Map.of("orderNo", "ORD-2"), Map.class);

        assertThat(result).containsEntry("orderNo", "ORD-2");
        verify(repository).complete(eq(9L), anyString());
    }

    @Test
    void reclaimGivesTheRetryAFullWindowInsteadOfTheOriginalExpiry() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.FAILED, null, HASH)));
        when(repository.reclaim(anyLong(), any(Instant.class))).thenReturn(true);

        Instant before = Instant.now();
        guard.execute(1L, OPERATION, KEY, HASH, () -> Map.of("orderNo", "ORD-2"), Map.class);

        // A reclaimed record that kept its original expires_at could be swept mid-run, flipping
        // it to FAILED while the action is still executing -- which lets a second retry run the
        // same action concurrently.
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).reclaim(eq(9L), captor.capture());
        assertThat(captor.getValue())
                .isAfter(before.plus(Duration.ofHours(23)))
                .isBefore(Instant.now().plus(Duration.ofHours(25)));
    }

    @Test
    void losingTheReclaimRaceRefusesInsteadOfRunningTheActionAgain() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.FAILED, null, HASH)))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.IN_PROGRESS, null, HASH)));
        when(repository.reclaim(anyLong(), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> guard.execute(1L, OPERATION, KEY, HASH, () -> Map.of(), Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        verify(repository, never()).complete(anyLong(), anyString());
    }

    @Test
    void losingTheRaceToAnAlreadyCompletedKeyReplaysIt() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.FAILED, null, HASH)))
                .thenReturn(
                        Optional.of(
                                record(
                                        9L,
                                        IdempotencyStatus.COMPLETED,
                                        "{\"orderNo\":\"ORD-1\"}",
                                        HASH)));
        when(repository.reclaim(anyLong(), any(Instant.class))).thenReturn(false);

        Map<String, Object> replayed =
                guard.execute(
                        1L,
                        OPERATION,
                        KEY,
                        HASH,
                        () -> {
                            throw new AssertionError("the action must not run twice");
                        },
                        Map.class);

        assertThat(replayed).containsEntry("orderNo", "ORD-1");
    }

    @Test
    void aFailingActionIsRecordedAndTheErrorSurfaces() {
        when(repository.tryClaim(any()))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.IN_PROGRESS, null, HASH)));

        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        1L,
                                        OPERATION,
                                        KEY,
                                        HASH,
                                        () -> {
                                            throw new IllegalStateException("upstream said no");
                                        },
                                        Map.class))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).markFailed(9L);
        verify(repository, never()).complete(anyLong(), anyString());
    }

    @Test
    void aResponseWriteFailureIsNotAFailedActionAndMustNotInviteARerun() {
        when(repository.tryClaim(any()))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.IN_PROGRESS, null, HASH)));
        org.mockito.Mockito.doThrow(new IllegalStateException("data too long for column"))
                .when(repository)
                .complete(anyLong(), anyString());

        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        1L,
                                        OPERATION,
                                        KEY,
                                        HASH,
                                        () -> Map.of("orderNo", "ORD-1"),
                                        Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_UNKNOWN);

        verify(repository).markResultUnknown(9L);
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void aRetryAfterALostResponseIsRefusedWithoutRunningTheActionAgain() {
        when(repository.tryClaim(any())).thenReturn(Optional.empty());
        when(repository.find(1L, OPERATION, KEY))
                .thenReturn(Optional.of(record(9L, IdempotencyStatus.RESULT_UNKNOWN, null, HASH)));

        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        1L,
                                        OPERATION,
                                        KEY,
                                        HASH,
                                        () -> {
                                            throw new AssertionError(
                                                    "the action must not run again");
                                        },
                                        Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_UNKNOWN);

        verify(repository, never()).reclaim(anyLong(), any(Instant.class));
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void anOverlongKeyIsRefusedUpfrontRatherThanByTheDatabase() {
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        1L,
                                        OPERATION,
                                        "k".repeat(129),
                                        HASH,
                                        () -> Map.of(),
                                        Map.class))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(repository, never()).tryClaim(any());
    }

    @Test
    void theJsonFingerprintSeparatesStructuresTheOldStringJoinWouldConfuse() {
        // The old fingerprint joined lines as "sku:qty,sku:qty", so a sku containing those
        // separators spelled the same string as two separate lines -- a reused key would replay
        // the wrong order instead of returning a conflict.
        var twoLines =
                new CreateOrderRequest(
                        List.of(
                                new CreateOrderRequest.Line("A", 1),
                                new CreateOrderRequest.Line("B", 2)));
        var trickySku = new CreateOrderRequest(List.of(new CreateOrderRequest.Line("A:1,B", 2)));

        assertThat(guard.hashOfJson(twoLines)).hasSize(64);
        assertThat(guard.hashOfJson(twoLines)).isEqualTo(guard.hashOfJson(twoLines));
        assertThat(guard.hashOfJson(twoLines)).isNotEqualTo(guard.hashOfJson(trickySku));
    }

    @Test
    void theRequestHashIsStableAndDependsOnEveryPart() {
        assertThat(IdempotencyGuard.hashOf("a", "b")).hasSize(64);
        assertThat(IdempotencyGuard.hashOf("a", "b")).isEqualTo(IdempotencyGuard.hashOf("a", "b"));
        assertThat(IdempotencyGuard.hashOf("a", "b"))
                .isNotEqualTo(IdempotencyGuard.hashOf("ab", ""));
        assertThat(IdempotencyGuard.hashOf(null, "b")).isEqualTo(IdempotencyGuard.hashOf("", "b"));
    }

    private IdempotencyRecord record(
            Long id, IdempotencyStatus status, String responseJson, String requestHash) {
        Instant now = Instant.now();
        return new IdempotencyRecord(
                id,
                1L,
                OPERATION,
                KEY,
                requestHash,
                status,
                null,
                responseJson,
                now,
                now,
                now.plusSeconds(3600));
    }
}
