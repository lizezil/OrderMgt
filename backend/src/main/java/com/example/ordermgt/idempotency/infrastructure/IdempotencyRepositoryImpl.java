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
    private final IdempotencyPersistenceMapper persistenceMapper;

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
                .map(persistenceMapper::toDomain);
    }

    @Override
    public Optional<IdempotencyRecord> tryClaim(IdempotencyRecord record) {
        IdempotencyRecordPO po = persistenceMapper.toPo(record);
        try {
            mapper.insert(po);
            return Optional.of(persistenceMapper.toDomain(po));
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
    public void markResultUnknown(Long id) {
        IdempotencyRecordPO patch = new IdempotencyRecordPO();
        patch.setId(id);
        patch.setStatus(IdempotencyStatus.RESULT_UNKNOWN.name());
        patch.setCompletedAt(Instant.now());
        mapper.updateById(patch);
    }

    @Override
    public boolean reclaim(Long id, Instant newExpiresAt) {
        IdempotencyRecordPO patch = new IdempotencyRecordPO();
        patch.setStatus(IdempotencyStatus.IN_PROGRESS.name());
        patch.setCompletedAt(null);
        patch.setExpiresAt(newExpiresAt);
        // WHERE status = 'FAILED' is the whole point: of N threads retrying a failed key, only
        // the one whose UPDATE actually matches a row gets to run the action again.
        return mapper.update(
                        patch,
                        Wrappers.<IdempotencyRecordPO>lambdaUpdate()
                                .eq(IdempotencyRecordPO::getId, id)
                                .eq(
                                        IdempotencyRecordPO::getStatus,
                                        IdempotencyStatus.FAILED.name()))
                > 0;
    }

    @Override
    public int deleteExpired(Instant now) {
        return mapper.delete(
                Wrappers.<IdempotencyRecordPO>lambdaQuery()
                        .le(IdempotencyRecordPO::getExpiresAt, now));
    }
}
