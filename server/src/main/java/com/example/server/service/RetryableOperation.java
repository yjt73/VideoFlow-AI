package com.example.server.service;

@FunctionalInterface
public interface RetryableOperation<T> {

    T call() throws Exception;
}
