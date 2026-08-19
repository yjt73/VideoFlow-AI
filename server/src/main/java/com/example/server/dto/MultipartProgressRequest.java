package com.example.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MultipartProgressRequest(
        @NotEmpty @Size(max = 1000) List<@Min(1) Integer> partNumbers
) {
}
