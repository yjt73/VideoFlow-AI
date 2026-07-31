package com.example.server.ai.retry;

@FunctionalInterface
public interface RetryAttemptLogger {

    void log(RetryAttemptLog log);

    static RetryAttemptLogger noop() {
        return ignored -> {
        };
    }
}
