package com.example.server.service;

import java.time.Duration;

public class ThirdPartyApiException extends RuntimeException {

    private final String apiName;
    private final int statusCode;
    private final Duration retryAfter;

    public ThirdPartyApiException(
            String apiName,
            int statusCode,
            String message,
            Duration retryAfter
    ) {
        super(message);
        this.apiName = apiName;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public String apiName() {
        return apiName;
    }

    public int statusCode() {
        return statusCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
