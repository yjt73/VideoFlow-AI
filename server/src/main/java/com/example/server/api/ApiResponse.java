package com.example.server.api;

import java.time.Instant;
import java.util.UUID;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String requestId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "成功", data, newRequestId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, newRequestId(), Instant.now());
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
