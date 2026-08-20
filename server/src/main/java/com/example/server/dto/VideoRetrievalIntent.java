package com.example.server.dto;

import java.util.List;

public record VideoRetrievalIntent(
        String semanticQuery,
        List<String> keywords,
        List<String> visualKeywords
) {
    public VideoRetrievalIntent {
        semanticQuery = semanticQuery == null ? "" : semanticQuery.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        visualKeywords = visualKeywords == null ? List.of() : List.copyOf(visualKeywords);
    }
}
