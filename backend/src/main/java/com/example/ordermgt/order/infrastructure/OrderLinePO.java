package com.example.ordermgt.order.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

@Data
@TableName("order_lines")
public class OrderLinePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String sku;
    private String itemName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineAmount;
}
