package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("conversations")
public class ConversationPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;
    private String title;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastMessageAt;
}
