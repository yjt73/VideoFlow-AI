package com.example.server.service;

public record RetryAttemptLog(
        String apiName,
        String taskId,
        int attempt,
        long elapsedMillis,
        String failureType,
        String message
) {
}
