package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("ai_invocations")
public class AiInvocationPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long customerId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private String outcome;
    private String error;
    private String toolCallsJson;
    private Instant createdAt;
}
