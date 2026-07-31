package com.example.server.qa.api;

public record LibraryQaCitationView(
        Long mediaId,
        String filename,
        Long timestampMs,
        Long endMs,
        String source,
        String content
) {
}
