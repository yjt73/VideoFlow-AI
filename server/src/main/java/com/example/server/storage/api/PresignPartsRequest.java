package com.example.server.storage.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PresignPartsRequest(
        @NotEmpty @Size(max = 20) List<@Positive Integer> partNumbers
) {
}
