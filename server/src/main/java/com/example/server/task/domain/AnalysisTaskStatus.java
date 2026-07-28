package com.example.server.task.domain;

/** 视频分析任务的持久化状态。 */
public enum AnalysisTaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    RETRYING,
    SUCCESS,
    FAILED,
    DEAD,
    CANCELLED
}
