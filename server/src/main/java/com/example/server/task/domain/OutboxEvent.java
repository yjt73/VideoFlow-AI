package com.example.server.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 与业务事务一同写入、稍后可靠投递到 MQ 的事件。 */
@Data
@TableName("outbox_events")
public class OutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String aggregateId;
    private String eventType;
    private String topic;
    private String tag;
    private String payload;
    private OutboxStatus status;
    private Integer publishAttempts;
    private String lastError;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
}
