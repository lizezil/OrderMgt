package com.example.ordermgt.assistant.infrastructure.persist;

import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Row and domain for model-call audit. The outcome travels as text, so the read direction converts
 * it explicitly rather than relying on MapStruct's enum heuristic.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AiInvocationPersistenceMapper {

    AiInvocationPO toPo(AiInvocation invocation);

    default AiInvocation toDomain(AiInvocationPO po) {
        return new AiInvocation(
                po.getId(),
                po.getConversationId(),
                po.getCustomerId(),
                po.getModel(),
                po.getPromptTokens(),
                po.getCompletionTokens(),
                po.getLatencyMs(),
                AiInvocationOutcome.valueOf(po.getOutcome()),
                po.getError(),
                po.getToolCallsJson(),
                po.getCreatedAt());
    }
}
