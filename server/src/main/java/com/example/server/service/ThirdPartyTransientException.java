package com.example.server.service;

public class ThirdPartyTransientException extends RuntimeException {

    public ThirdPartyTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
