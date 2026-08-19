package com.example.server.service;

@FunctionalInterface
public interface RetryAttemptLogger {

    void log(RetryAttemptLog log);

    static RetryAttemptLogger noop() {
        return ignored -> {
        };
    }
}
