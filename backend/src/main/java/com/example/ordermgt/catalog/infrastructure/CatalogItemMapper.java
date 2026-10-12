package com.example.ordermgt.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogItemMapper extends BaseMapper<CatalogItemPO> {
}
