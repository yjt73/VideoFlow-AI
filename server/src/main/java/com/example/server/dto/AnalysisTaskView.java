package com.example.server.dto;

import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.AnalysisTaskType;

import java.time.LocalDateTime;

/** 对前端公开的任务状态，不包含内部版本号等实现字段。 */
public record AnalysisTaskView(
        String taskId,
        Long mediaId,
        AnalysisTaskType taskType,
        AnalysisTaskStatus status,
        Integer progress,
        String currentStage,
        Integer retryCount,
        String errorCode,
        String errorMessage,
        String resultLocation,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt
) {

    public static AnalysisTaskView from(AnalysisTask task) {
        return new AnalysisTaskView(
                task.getTaskId(),
                task.getMediaId(),
                task.getTaskType(),
                task.getStatus(),
                task.getProgress(),
                task.getCurrentStage(),
                task.getRetryCount(),
                task.getErrorCode(),
                task.getErrorMessage(),
                task.getResultLocation(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getUpdatedAt()
        );
    }
}
