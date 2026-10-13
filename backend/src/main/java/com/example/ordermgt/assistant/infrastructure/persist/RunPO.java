package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("runs")
public class RunPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String status;
    private String error;
    private Instant startedAt;
    private Instant endedAt;
}
