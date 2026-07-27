package com.example.server.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public int code() {
        return status.value();
    }

    public HttpStatus status() {
        return status;
    }
}
