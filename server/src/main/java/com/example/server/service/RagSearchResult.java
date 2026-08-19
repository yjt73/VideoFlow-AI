package com.example.server.service;

public record RagSearchResult(
        Long segmentId,
        Long mediaId,
        Integer segmentIndex,
        Long startMs,
        Long endMs,
        String source,
        String content,
        double keywordScore,
        double vectorScore,
        double fusedScore
) {
}
