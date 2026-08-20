package com.example.server.dto;

import java.util.List;

public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts
) {
    public VideoEvidenceHit {
        source = source == null ? "UNKNOWN" : source.trim();
        snippet = snippet == null ? "" : snippet.trim();
        transcript = transcript == null ? "" : transcript.trim();
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
    }
}
