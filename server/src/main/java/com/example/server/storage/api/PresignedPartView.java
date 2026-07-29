package com.example.server.storage.api;

import java.time.LocalDateTime;

public record PresignedPartView(
        int partNumber,
        String url,
        LocalDateTime expiresAt
) {
}
