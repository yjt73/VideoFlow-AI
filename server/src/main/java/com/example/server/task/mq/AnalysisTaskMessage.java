package com.example.server.task.mq;

import java.io.Serializable;

/** Outbox 和 RocketMQ 之间传递的稳定分析任务消息。 */
public record AnalysisTaskMessage(
        String taskId,
        Long mediaId,
        String taskType,
        String userGoal
) implements Serializable {
}
