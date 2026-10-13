package com.example.ordermgt.assistant.infrastructure.persist;

import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
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
}
