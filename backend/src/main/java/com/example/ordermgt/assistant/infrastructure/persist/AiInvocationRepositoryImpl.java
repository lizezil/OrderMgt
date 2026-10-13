package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class AiInvocationRepositoryImpl implements AiInvocationRepository {

    private final AiInvocationMapper mapper;
    private final AiInvocationPersistenceMapper persistenceMapper;

    @Override
    public AiInvocation save(AiInvocation invocation) {
        AiInvocationPO po = persistenceMapper.toPo(invocation);
        mapper.insert(po);
        return persistenceMapper.toDomain(po);
    }

    @Override
    public List<AiInvocation> findByConversationId(Long conversationId) {
        // created_at DESC rides the (conversation_id, created_at) index; id breaks same-ms ties.
        return mapper
                .selectList(
                        Wrappers.<AiInvocationPO>lambdaQuery()
                                .eq(AiInvocationPO::getConversationId, conversationId)
                                .orderByDesc(AiInvocationPO::getCreatedAt)
                                .orderByDesc(AiInvocationPO::getId))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }
}
