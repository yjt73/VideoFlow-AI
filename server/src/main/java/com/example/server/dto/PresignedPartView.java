package com.example.server.dto;

import java.time.LocalDateTime;

public record PresignedPartView(
        int partNumber,
        String url,
        LocalDateTime expiresAt
) {
}
