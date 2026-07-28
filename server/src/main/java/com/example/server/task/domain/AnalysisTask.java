package com.example.server.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 独立保存视频分析执行状态的任务记录。 */
@Data
@TableName("analysis_tasks")
public class AnalysisTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private Long mediaId;
    private Long userId;
    private AnalysisTaskType taskType;
    private AnalysisTaskStatus status;
    private Integer retryCount;
    private Integer compensationCount;
    private Integer progress;
    private String currentStage;
    private String errorCode;
    private String errorMessage;
    private String resultLocation;
    private String userGoal;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
