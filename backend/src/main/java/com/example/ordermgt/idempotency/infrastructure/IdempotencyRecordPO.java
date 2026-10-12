package com.example.ordermgt.idempotency.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("idempotency_records")
public class IdempotencyRecordPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private String operation;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private String resultRef;
    private String responseJson;
    private Instant createdAt;
    private Instant completedAt;
    private Instant expiresAt;
}
