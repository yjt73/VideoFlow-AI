package com.example.server.dto;

import com.example.server.task.domain.AnalysisTaskStatus;

import java.time.LocalDateTime;

/** 创建异步任务后立即返回的稳定标识和初始状态。 */
public record AnalysisTaskCreatedView(
        String taskId,
        AnalysisTaskStatus status,
        LocalDateTime createdAt
) {
}
