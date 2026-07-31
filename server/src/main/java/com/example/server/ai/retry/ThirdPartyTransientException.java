package com.example.server.ai.retry;

public class ThirdPartyTransientException extends RuntimeException {

    public ThirdPartyTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
