package com.example.ordermgt.idempotency.application;

import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per idempotency key.
 *
 * The claim is a plain INSERT against the unique key `(customer_id, operation,
 * idempotency_key)` -- no read-then-write, so two concurrent retries cannot both win.
 * The winner then runs: claim IN_PROGRESS -> action -> COMPLETED with the response payload.
 *
 * A retry with the same key and the same request hash gets the stored payload replayed --
 * the action, which may be a paid model call or an order insert, does not run twice. A
 * retry with a *different* request hash means the key was reused for new work, which is an
 * error rather than a replay.
 *
 * Deliberately NOT @Transactional: the guarded action may contain an AI call, and no AI
 * call may hold a database transaction open (ADR-0002). Stated plainly, the consequence is
 * that a crash between the action committing and the record being marked COMPLETED leaves
 * the key IN_PROGRESS, and retries are refused until it expires. Sweeping expired
 * IN_PROGRESS records is the fix and is out of scope here.
 */
@Slf4j
@Component
public class IdempotencyGuard {

    /** The header callers use to supply a key. */
    public static final String KEY_HEADER = "Idempotency-Key";

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration retention;

    public IdempotencyGuard(IdempotencyRepository repository,
                            ObjectMapper objectMapper,
                            @Value("${idempotency.retention-hours:24}") long retentionHours) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.retention = Duration.ofHours(retentionHours);
    }

    /**
     * @param idempotencyKey the caller's key; null or blank opts out and runs every time
     * @param requestHash    fingerprint of the request body, to catch a reused key
     * @param action         the work to run at most once
     * @param responseType   the payload type kept for replay
     */
    public <T> T execute(Long customerId, String operation, String idempotencyKey, String requestHash,
                         Supplier<T> action, Class<T> responseType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }

        Claim<T> claim = claim(customerId, operation, idempotencyKey, requestHash, responseType);
        if (claim.isReplay()) {
            return claim.replayedValue();
        }

        try {
            T value = action.get();
            repository.complete(claim.recordId(), write(value));
            return value;
        } catch (RuntimeException ex) {
            // Best effort: a failed attempt must not block a corrected retry.
            repository.markFailed(claim.recordId());
            throw ex;
        }
    }

    private <T> Claim<T> claim(Long customerId, String operation, String idempotencyKey,
                               String requestHash, Class<T> responseType) {
        Instant now = Instant.now();
        IdempotencyRecord candidate = new IdempotencyRecord(null, customerId, operation, idempotencyKey,
                requestHash, IdempotencyStatus.IN_PROGRESS, null, null, now, null, now.plus(retention));

        var claimed = repository.tryClaim(candidate);
        if (claimed.isPresent()) {
            return Claim.claimed(claimed.get().id());
        }

        IdempotencyRecord existing = repository.find(customerId, operation, idempotencyKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "幂等键争用后未能读回记录"));

        if (requestHash != null && existing.requestHash() != null
                && !requestHash.equals(existing.requestHash())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "幂等键 " + idempotencyKey + " 已被用于另一个不同的请求");
        }

        return switch (existing.status()) {
            case COMPLETED -> Claim.replayed(read(existing.responseJson(), responseType));
            case IN_PROGRESS -> throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "相同幂等键的请求仍在处理中，请稍后重试");
            case FAILED -> {
                log.info("retrying previously failed idempotent operation {} key={}", operation, idempotencyKey);
                repository.reclaim(existing.id());
                yield Claim.claimed(existing.id());
            }
        };
    }

    /**
     * Either "you own the key, run the action" (recordId set) or "here is the answer you
     * already got" (replayedValue set).
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

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等响应序列化失败");
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等记录缺少响应内容");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等响应反序列化失败");
        }
    }
}
