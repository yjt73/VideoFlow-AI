package com.example.server.storage.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MultipartProgressRequest(
        @NotEmpty @Size(max = 1000) List<@Valid CompletedPartRequest> parts
) {
}
