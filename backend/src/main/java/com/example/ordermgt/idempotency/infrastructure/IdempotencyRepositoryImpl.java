package com.example.ordermgt.idempotency.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class IdempotencyRepositoryImpl implements IdempotencyRepository {

    private final IdempotencyRecordMapper mapper;

    @Override
    public Optional<IdempotencyRecord> find(
            Long customerId, String operation, String idempotencyKey) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.<IdempotencyRecordPO>lambdaQuery()
                                        .eq(IdempotencyRecordPO::getCustomerId, customerId)
                                        .eq(IdempotencyRecordPO::getOperation, operation)
                                        .eq(
                                                IdempotencyRecordPO::getIdempotencyKey,
                                                idempotencyKey)))
                .map(this::toDomain);
    }

    @Override
    public Optional<IdempotencyRecord> tryClaim(IdempotencyRecord record) {
        IdempotencyRecordPO po = toPo(record);
        try {
            mapper.insert(po);
            return Optional.of(toDomain(po));
        } catch (DataIntegrityViolationException duplicate) {
            // Someone else holds this key. The DB unique constraint is what makes this
            // safe under concurrency -- two threads cannot both win the insert.
            return Optional.empty();
        }
    }

    @Override
    public void complete(Long id, String responseJson) {
        IdempotencyRecordPO patch = new IdempotencyRecordPO();
        patch.setId(id);
        patch.setStatus(IdempotencyStatus.COMPLETED.name());
        patch.setResponseJson(responseJson);
        patch.setCompletedAt(Instant.now());
        mapper.updateById(patch);
    }

    @Override
    public void markFailed(Long id) {
        IdempotencyRecordPO patch = new IdempotencyRecordPO();
        patch.setId(id);
        patch.setStatus(IdempotencyStatus.FAILED.name());
        patch.setCompletedAt(Instant.now());
        mapper.updateById(patch);
    }

    @Override
    public void reclaim(Long id) {
        IdempotencyRecordPO patch = new IdempotencyRecordPO();
        patch.setId(id);
        patch.setStatus(IdempotencyStatus.IN_PROGRESS.name());
        patch.setCompletedAt(null);
        mapper.updateById(patch);
    }

    private IdempotencyRecordPO toPo(IdempotencyRecord record) {
        IdempotencyRecordPO po = new IdempotencyRecordPO();
        po.setId(record.id());
        po.setCustomerId(record.customerId());
        po.setOperation(record.operation());
        po.setIdempotencyKey(record.idempotencyKey());
        po.setRequestHash(record.requestHash());
        po.setStatus(record.status().name());
        po.setResultRef(record.resultRef());
        po.setResponseJson(record.responseJson());
        po.setCreatedAt(record.createdAt());
        po.setCompletedAt(record.completedAt());
        po.setExpiresAt(record.expiresAt());
        return po;
    }

    private IdempotencyRecord toDomain(IdempotencyRecordPO po) {
        return new IdempotencyRecord(
                po.getId(),
                po.getCustomerId(),
                po.getOperation(),
                po.getIdempotencyKey(),
                po.getRequestHash(),
                IdempotencyStatus.valueOf(po.getStatus()),
                po.getResultRef(),
                po.getResponseJson(),
                po.getCreatedAt(),
                po.getCompletedAt(),
                po.getExpiresAt());
    }
}
