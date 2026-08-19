package com.example.server.dto;

public record LibraryQaCitationView(
        Long mediaId,
        String filename,
        Long timestampMs,
        Long endMs,
        String source,
        String content
) {
}
