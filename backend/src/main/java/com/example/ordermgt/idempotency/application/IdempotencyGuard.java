package com.example.ordermgt.idempotency.application;

import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs an operation at most once per idempotency key.
 *
 * <p>The claim is a plain INSERT against the unique key `(customer_id, operation, idempotency_key)`
 * -- no read-then-write, so two concurrent retries cannot both win. The winner then runs: claim
 * IN_PROGRESS -> action -> COMPLETED with the response payload.
 *
 * <p>A retry with the same key and the same request hash gets the stored payload replayed -- the
 * action, which may be a paid model call or an order insert, does not run twice. A retry with a
 * *different* request hash means the key was reused for new work, which is an error rather than a
 * replay.
 *
 * <p>Deliberately NOT @Transactional: the guarded action may contain an AI call, and no AI call may
 * hold a database transaction open (ADR-0002). Stated plainly, the consequence is that a crash
 * between the action committing and the record being marked COMPLETED leaves the key IN_PROGRESS,
 * and retries are refused until the key expires. {@code IdempotencySweeper} then fails those stale
 * keys, which reopens them for a retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    /** The header callers use to supply a key. */
    public static final String KEY_HEADER = "Idempotency-Key";

    /** Matches idempotency_records.idempotency_key VARCHAR(128); enforced here, not by the DB. */
    public static final int KEY_MAX = 128;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    /** Configured in hours (the shape it arrives in); {@link #retention()} is how it is used. */
    @Value("${idempotency.retention-hours:24}")
    private final long retentionHours;

    /**
     * @param idempotencyKey the caller's key; null or blank opts out and runs every time
     * @param requestHash fingerprint of the request body, to catch a reused key
     * @param action the work to run at most once
     * @param responseType the payload type kept for replay
     */
    public <T> T execute(
            Long customerId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Supplier<T> action,
            Class<T> responseType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        if (idempotencyKey.length() > KEY_MAX) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most " + KEY_MAX + " characters");
        }

        Claim<T> claim = claim(customerId, operation, idempotencyKey, requestHash, responseType);
        if (claim.isReplay()) {
            return claim.replayedValue();
        }

        T value;
        try {
            value = action.get();
        } catch (RuntimeException ex) {
            // The action itself failed: nothing committed, so a corrected retry must be allowed.
            repository.markFailed(claim.recordId());
            throw ex;
        }
        try {
            repository.complete(claim.recordId(), write(value));
        } catch (RuntimeException ex) {
            // The action already committed. Marking FAILED here would let a retry run it again --
            // a duplicate order -- so the key is sealed as RESULT_UNKNOWN and retries refused.
            log.error(
                    "action succeeded but its response could not be stored, key={}: {}",
                    idempotencyKey,
                    ex.getMessage());
            repository.markResultUnknown(claim.recordId());
            throw new BusinessException(
                    ErrorCode.RESULT_UNKNOWN, ErrorCode.RESULT_UNKNOWN.defaultMessage());
        }
        return value;
    }

    private <T> Claim<T> claim(
            Long customerId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType) {
        Instant now = Instant.now();
        IdempotencyRecord candidate =
                new IdempotencyRecord(
                        null,
                        customerId,
                        operation,
                        idempotencyKey,
                        requestHash,
                        IdempotencyStatus.IN_PROGRESS,
                        null,
                        null,
                        now,
                        null,
                        now.plus(retention()));

        var claimed = repository.tryClaim(candidate);
        if (claimed.isPresent()) {
            return Claim.claimed(claimed.get().id());
        }

        IdempotencyRecord existing =
                repository
                        .find(customerId, operation, idempotencyKey)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INTERNAL_ERROR,
                                                "idempotency record missing after claim "
                                                        + "contention"));

        if (requestHash != null
                && existing.requestHash() != null
                && !requestHash.equals(existing.requestHash())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key "
                            + idempotencyKey
                            + " was already used for a different request");
        }

        return switch (existing.status()) {
            case COMPLETED -> Claim.replayed(read(existing.responseJson(), responseType));
            case IN_PROGRESS -> throw inProgress();
            case RESULT_UNKNOWN -> throw resultUnknown();
            case FAILED -> {
                log.info(
                        "retrying previously failed idempotent operation {} key={}",
                        operation,
                        idempotencyKey);
                if (repository.reclaim(existing.id(), now.plus(retention()))) {
                    yield Claim.claimed(existing.id());
                }
                // Lost the reclaim race: another retrying thread already flipped this record out
                // of FAILED (and may even have finished). Answer from the record's current state
                // instead of running the action a second time.
                IdempotencyRecord raced =
                        repository
                                .find(customerId, operation, idempotencyKey)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.INTERNAL_ERROR,
                                                        "idempotency record missing after reclaim "
                                                                + "contention"));
                yield switch (raced.status()) {
                    case COMPLETED -> Claim.replayed(read(raced.responseJson(), responseType));
                    case IN_PROGRESS, FAILED -> throw inProgress();
                    case RESULT_UNKNOWN -> throw resultUnknown();
                };
            }
        };
    }

    private static BusinessException inProgress() {
        return new BusinessException(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "same idempotency key request is still in progress, retry later");
    }

    private static BusinessException resultUnknown() {
        return new BusinessException(
                ErrorCode.RESULT_UNKNOWN, ErrorCode.RESULT_UNKNOWN.defaultMessage());
    }

    private Duration retention() {
        return Duration.ofHours(retentionHours);
    }

    /**
     * Either "you own the key, run the action" (recordId set) or "here is the answer you already
     * got" (replayedValue set).
     */
    private record Claim<T>(Long recordId, T replayedValue) {

        static <T> Claim<T> claimed(Long recordId) {
            return new Claim<>(recordId, null);
        }

        static <T> Claim<T> replayed(T value) {
            return new Claim<>(null, value);
        }

        boolean isReplay() {
            return recordId == null;
        }
    }

    /** SHA-256 hex, matching idempotency_records.request_hash CHAR(64). */
    public static String hashOf(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Fingerprints a request by its canonical JSON, so field content can never impersonate a
     * separator the way a hand-joined {@code "sku:qty,sku:qty"} string could.
     */
    public String hashOfJson(Object request) {
        try {
            return hashOf(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "failed to fingerprint the request body");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "failed to serialize idempotent response");
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "idempotency record has no stored response");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "failed to deserialize idempotent response");
        }
    }
}
