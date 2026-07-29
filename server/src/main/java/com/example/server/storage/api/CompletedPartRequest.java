package com.example.server.storage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CompletedPartRequest(
        @Positive int partNumber,
        @NotBlank String etag
) {
}
