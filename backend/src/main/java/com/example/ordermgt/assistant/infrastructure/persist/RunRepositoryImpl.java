package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.RunStatus;
import java.util.Collection;
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
}
