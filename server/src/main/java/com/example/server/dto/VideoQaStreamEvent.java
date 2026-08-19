package com.example.server.dto;

public record VideoQaStreamEvent(
        String type,
        String message,
        Object data
) {
}
