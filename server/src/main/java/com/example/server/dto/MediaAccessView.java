package com.example.server.dto;

import java.time.LocalDateTime;

public record MediaAccessView(String url, LocalDateTime expiresAt) {
}
