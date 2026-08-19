package com.example.server.dto;

import com.example.server.task.domain.AnalysisTaskStatus;

/** 任务控制命令执行后的最新状态。 */
public record TaskCommandView(
        String taskId,
        AnalysisTaskStatus status
) {
}
