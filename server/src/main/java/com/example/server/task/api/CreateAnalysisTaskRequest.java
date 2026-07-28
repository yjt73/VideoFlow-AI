package com.example.server.task.api;

import jakarta.validation.constraints.Size;

/** 创建视频分析任务时可选的用户目标。 */
public record CreateAnalysisTaskRequest(
        @Size(max = 500, message = "分析目标不能超过 500 字")
        String userGoal
) {
}
