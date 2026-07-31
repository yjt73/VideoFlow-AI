package com.example.server.ai.artifact;

public record CheckpointKey(
        String taskId,
        Long mediaId,
        String taskType,
        String stage,
        String goalHash,
        String inputHash,
        String modelVersion
) {
}
