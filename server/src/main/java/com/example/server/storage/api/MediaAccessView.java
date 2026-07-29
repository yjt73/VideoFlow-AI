package com.example.server.storage.api;

import java.time.LocalDateTime;

public record MediaAccessView(String url, LocalDateTime expiresAt) {
}
