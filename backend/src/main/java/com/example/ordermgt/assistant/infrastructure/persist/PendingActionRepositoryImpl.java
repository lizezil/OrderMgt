package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
        // The row lock is what makes "confirm twice" a no-op instead of a double
        // execution: the second transaction blocks here until the first commits.
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
    public Optional<PendingAction> findLatestConfirmed(Long customerId, Long conversationId) {
        if (customerId == null || conversationId == null) {
            return Optional.empty();
        }
        // Confirmed most recently wins: resolvedAt is when the customer said yes, and two confirms
        // can share a millisecond, so id breaks the tie the way insertion order would.
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.<PendingActionPO>lambdaQuery()
                                        .eq(PendingActionPO::getCustomerId, customerId)
                                        .eq(PendingActionPO::getConversationId, conversationId)
                                        .eq(
                                                PendingActionPO::getStatus,
                                                PendingActionStatus.CONFIRMED.name())
                                        .isNotNull(PendingActionPO::getResultRef)
                                        .orderByDesc(PendingActionPO::getResolvedAt)
                                        .orderByDesc(PendingActionPO::getId)
                                        .last("LIMIT 1")))
                .map(persistenceMapper::toDomain);
    }

    @Override
    public List<PendingAction> findLivePending(Long customerId, Long conversationId, Instant now) {
        if (customerId == null || conversationId == null) {
            return List.of();
        }
        // Oldest first: the list is read as the thread reads, so "the second one" means the second
        // proposal, not the second-most-recent row.
        return mapper
                .selectList(
                        Wrappers.<PendingActionPO>lambdaQuery()
                                .eq(PendingActionPO::getCustomerId, customerId)
                                .eq(PendingActionPO::getConversationId, conversationId)
                                .eq(PendingActionPO::getStatus, PendingActionStatus.PENDING.name())
                                .gt(PendingActionPO::getExpiresAt, now)
                                .orderByAsc(PendingActionPO::getId))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Set<Long> livePendingConversationIds(Collection<Long> conversationIds, Instant now) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Set.of();
        }
        return mapper
                .selectList(
                        Wrappers.<PendingActionPO>lambdaQuery()
                                .in(PendingActionPO::getConversationId, conversationIds)
                                .eq(PendingActionPO::getStatus, PendingActionStatus.PENDING.name())
                                .gt(PendingActionPO::getExpiresAt, now))
                .stream()
                .map(PendingActionPO::getConversationId)
                .collect(Collectors.toSet());
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
