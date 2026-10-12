package com.example.ordermgt.catalog.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("catalog_items")
public class CatalogItemPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sku;
    private String name;
    private BigDecimal unitPrice;
    private Boolean active;
    private Instant createdAt;
}
