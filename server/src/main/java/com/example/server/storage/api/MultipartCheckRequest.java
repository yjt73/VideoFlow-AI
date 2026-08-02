package com.example.server.storage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MultipartCheckRequest(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String contentHash,
        @Positive long sizeBytes,
        @NotBlank @Size(max = 128) String contentType,
        @Positive int totalParts,
        @Size(max = 36) String sessionId
) {
}
