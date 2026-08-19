package com.example.server.service;

@FunctionalInterface
public interface RetrySleeper {

    void sleep(long delayMillis);
}
