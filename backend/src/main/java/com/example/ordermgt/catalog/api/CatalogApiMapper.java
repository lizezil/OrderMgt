package com.example.ordermgt.catalog.api;

import com.example.ordermgt.catalog.domain.CatalogItem;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Domain item to wire shape. The id and the active flag stay internal. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CatalogApiMapper {

    CatalogItemResponse toResponse(CatalogItem item);
}
