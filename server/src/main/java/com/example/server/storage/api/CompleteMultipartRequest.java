package com.example.server.storage.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CompleteMultipartRequest(
        @NotEmpty List<@Valid CompletedPartRequest> parts
) {
}
