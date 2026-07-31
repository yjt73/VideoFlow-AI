package com.example.server.qa.api;

import java.util.List;

public record LibraryQaResponse(
        String conversationId,
        String answer,
        List<LibraryQaCitationView> citations
) {
}
