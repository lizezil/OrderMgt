package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class RunRepositoryImpl implements RunRepository {

    private final RunMapper runMapper;
    private final RunPersistenceMapper persistenceMapper;

    @Override
    public Run save(Run run) {
        RunPO po = persistenceMapper.toPo(run);
        if (run.id() == null) {
            runMapper.insert(po);
        } else {
            runMapper.updateById(po);
        }
        return persistenceMapper.toDomain(po);
    }

    @Override
    public Optional<Run> findById(Long runId) {
        return Optional.ofNullable(runMapper.selectById(runId)).map(persistenceMapper::toDomain);
    }

    @Override
    public Set<Long> runningConversationIds(Collection<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Set.of();
        }
        // The (conversation_id, status) index carries this: one query answers the whole sidebar.
        return runMapper
                .selectList(
                        Wrappers.<RunPO>lambdaQuery()
                                .in(RunPO::getConversationId, conversationIds)
                                .eq(RunPO::getStatus, RunStatus.RUNNING.name()))
                .stream()
                .map(RunPO::getConversationId)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<Long, Long> runningRunIds(Collection<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        return runMapper
                .selectList(
                        Wrappers.<RunPO>lambdaQuery()
                                .in(RunPO::getConversationId, conversationIds)
                                .eq(RunPO::getStatus, RunStatus.RUNNING.name()))
                .stream()
                // A conversation has at most one RUNNING run (ADR-0012), so the merge never fires;
                // naming one keeps the collector total if the invariant is ever broken.
                .collect(
                        Collectors.toMap(
                                RunPO::getConversationId, RunPO::getId, (first, second) -> first));
    }

    @Override
    public int interruptRunning(String reason, Instant at) {
        // The RUNNING predicate in the WHERE clause is the invariant, applied atomically: no row is
        // read first, so nothing can slip between a read and the write. Nothing outside RUNNING is
        // touched -- a terminal row is already the truth and must not be rewritten.
        return runMapper.update(
                null,
                Wrappers.<RunPO>lambdaUpdate()
                        .eq(RunPO::getStatus, RunStatus.RUNNING.name())
                        .set(RunPO::getStatus, RunStatus.INTERRUPTED.name())
                        .set(RunPO::getError, reason)
                        .set(RunPO::getEndedAt, at));
    }
}
