package com.example.ordermgt.assistant.infrastructure.persist;

import com.example.ordermgt.assistant.domain.Run;
import com.example.ordermgt.assistant.domain.RunStatus;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Row and domain for execution instances. The status travels as text, so the read direction
 * converts it explicitly rather than relying on MapStruct's enum heuristic (cf. {@code
 * AiInvocationPersistenceMapper}).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RunPersistenceMapper {

    RunPO toPo(Run run);

    default Run toDomain(RunPO po) {
        return Run.rehydrate(
                po.getId(),
                po.getConversationId(),
                RunStatus.valueOf(po.getStatus()),
                po.getError(),
                po.getStartedAt(),
                po.getEndedAt());
    }
}
