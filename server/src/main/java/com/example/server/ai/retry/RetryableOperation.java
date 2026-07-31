package com.example.server.ai.retry;

@FunctionalInterface
public interface RetryableOperation<T> {

    T call() throws Exception;
}
