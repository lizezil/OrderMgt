package com.example.ordermgt.catalog.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class CatalogRepositoryImpl implements CatalogRepository {

    private final CatalogItemMapper mapper;
    private final CatalogPersistenceMapper persistenceMapper;

    @Override
    public Optional<CatalogItem> findBySku(String sku) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                Wrappers.<CatalogItemPO>lambdaQuery()
                                        .eq(CatalogItemPO::getSku, sku)))
                .map(persistenceMapper::toDomain);
    }

    @Override
    public List<CatalogItem> findAllActive() {
        return mapper
                .selectList(
                        Wrappers.<CatalogItemPO>lambdaQuery()
                                .eq(CatalogItemPO::getActive, true)
                                .orderByAsc(CatalogItemPO::getId))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }
}
