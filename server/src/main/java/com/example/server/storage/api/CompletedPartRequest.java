package com.example.server.storage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CompletedPartRequest(
        @Positive int partNumber,
        @NotBlank @Size(max = 128) String etag
) {
}
