package com.example.server.dto;

import java.util.List;

public record LibraryQaResponse(
        String conversationId,
        String answer,
        List<LibraryQaCitationView> citations
) {
}
