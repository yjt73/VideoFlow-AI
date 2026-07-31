package com.example.server.ai.retry;

@FunctionalInterface
public interface RetrySleeper {

    void sleep(long delayMillis);
}
