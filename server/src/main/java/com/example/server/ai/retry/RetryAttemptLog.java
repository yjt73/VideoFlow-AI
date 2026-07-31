package com.example.server.ai.retry;

public record RetryAttemptLog(
        String apiName,
        String taskId,
        int attempt,
        long elapsedMillis,
        String failureType,
        String message
) {
}
