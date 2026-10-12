package com.example.ordermgt.catalog.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CatalogRepositoryImpl implements CatalogRepository {

    private final CatalogItemMapper mapper;

    public CatalogRepositoryImpl(CatalogItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<CatalogItem> findBySku(String sku) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<CatalogItemPO>lambdaQuery()
                        .eq(CatalogItemPO::getSku, sku)))
                .map(this::toDomain);
    }

    @Override
    public List<CatalogItem> findAllActive() {
        return mapper.selectList(Wrappers.<CatalogItemPO>lambdaQuery()
                        .eq(CatalogItemPO::getActive, true)
                        .orderByAsc(CatalogItemPO::getId))
                .stream().map(this::toDomain).toList();
    }

    private CatalogItem toDomain(CatalogItemPO po) {
        return new CatalogItem(po.getId(), po.getSku(), po.getName(), po.getUnitPrice(),
                Boolean.TRUE.equals(po.getActive()));
    }
}
