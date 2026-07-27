package com.example.server.api;

public class DependencyUnavailableException extends BusinessException {

    public DependencyUnavailableException(String message) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }

    public DependencyUnavailableException(String message, Throwable cause) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        initCause(cause);
    }
}
