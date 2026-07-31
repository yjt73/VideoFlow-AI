package com.example.server.qa.api;

public record VideoQaStreamEvent(
        String type,
        String message,
        Object data
) {
}
