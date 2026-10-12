package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("pending_actions")
public class PendingActionPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String token;
    private Long customerId;
    private Long conversationId;
    private Long sourceMessageId;
    private String toolName;
    private String argumentsJson;
    private String summary;
    private String status;
    private Instant expiresAt;
    private Instant resolvedAt;
    private String resultRef;
    private Instant createdAt;
}
