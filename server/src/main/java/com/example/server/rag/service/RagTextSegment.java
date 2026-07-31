package com.example.server.rag.service;

public record RagTextSegment(
        int segmentIndex,
        Long startMs,
        Long endMs,
        String source,
        String content
) {
}
