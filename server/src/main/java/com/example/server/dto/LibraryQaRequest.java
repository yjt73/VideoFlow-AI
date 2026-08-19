package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record LibraryQaRequest(
        String conversationId,
        @NotBlank String question,
        List<Long> mediaIds
) {
}
