package com.example.ordermgt.assistant.infrastructure.persist;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Row and domain for pending actions. The write direction is a plain bean mapping; the read
 * direction goes through the domain's {@code rehydrate} factory because {@link PendingAction} keeps
 * its constructor private and its status is an enum kept as text in the row.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PendingActionPersistenceMapper {

    PendingActionPO toPo(PendingAction action);

    default PendingAction toDomain(PendingActionPO po) {
        return PendingAction.rehydrate(
                po.getId(),
                po.getToken(),
                po.getCustomerId(),
                po.getConversationId(),
                po.getSourceMessageId(),
                po.getToolName(),
                po.getArgumentsJson(),
                po.getSummary(),
                PendingActionStatus.valueOf(po.getStatus()),
                po.getExpiresAt(),
                po.getResolvedAt(),
                po.getResultRef(),
                po.getCreatedAt());
    }
}
