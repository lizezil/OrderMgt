package com.example.ordermgt.catalog.infrastructure;

import com.example.ordermgt.catalog.domain.CatalogItem;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Row to domain. {@code active} is a boxed Boolean in the row and a plain flag in the domain. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CatalogPersistenceMapper {

    CatalogItem toDomain(CatalogItemPO po);
}
