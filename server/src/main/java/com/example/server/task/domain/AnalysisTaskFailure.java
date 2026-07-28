package com.example.server.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 保存任务每次失败、死信和补偿的审计信息。 */
@Data
@TableName("analysis_task_failures")
public class AnalysisTaskFailure {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String messageId;
    private String failureType;
    private Integer retryCount;
    private String errorCode;
    private String errorMessage;
    private String stackTrace;
    private String rawMessage;
    private LocalDateTime createdAt;
}
