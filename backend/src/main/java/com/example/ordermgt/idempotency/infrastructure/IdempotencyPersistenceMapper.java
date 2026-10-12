package com.example.ordermgt.idempotency.infrastructure;

import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Row and domain record, in both directions. The status enum travels as its name. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IdempotencyPersistenceMapper {

    IdempotencyRecordPO toPo(IdempotencyRecord record);

    IdempotencyRecord toDomain(IdempotencyRecordPO po);
}
