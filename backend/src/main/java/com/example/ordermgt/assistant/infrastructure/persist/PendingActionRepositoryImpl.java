package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class PendingActionRepositoryImpl implements PendingActionRepository {

    private final PendingActionMapper mapper;
    private final PendingActionPersistenceMapper persistenceMapper;

    @Override
    public PendingAction save(PendingAction action) {
        PendingActionPO po = persistenceMapper.toPo(action);
        if (action.getId() == null) {
            mapper.insert(po);
            action.assignId(po.getId());
        } else {
            mapper.updateById(po);
        }
        return action;
    }

    @Override
    public Optional<PendingAction> findByToken(String token) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.<PendingActionPO>lambdaQuery()
                                        .eq(PendingActionPO::getToken, token)))
                .map(persistenceMapper::toDomain);
    }

    @Override
    public Optional<PendingAction> lockByToken(String token) {
        // `FOR UPDATE` is accepted by both MySQL and H2 in MySQL mode. It is what makes
        // "confirm twice" a no-op instead of a double execution.
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.<PendingActionPO>lambdaQuery()
                                        .eq(PendingActionPO::getToken, token)
                                        .last("FOR UPDATE")))
                .map(persistenceMapper::toDomain);
    }

    @Override
    public List<PendingAction> findByIdIn(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper
                .selectList(Wrappers.<PendingActionPO>lambdaQuery().in(PendingActionPO::getId, ids))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public int expireOverdue(Instant now) {
        PendingActionPO patch = new PendingActionPO();
        patch.setStatus(PendingActionStatus.EXPIRED.name());
        patch.setResolvedAt(now);
        return mapper.update(
                patch,
                Wrappers.<PendingActionPO>lambdaUpdate()
                        .eq(PendingActionPO::getStatus, PendingActionStatus.PENDING.name())
                        .le(PendingActionPO::getExpiresAt, now));
    }
}
