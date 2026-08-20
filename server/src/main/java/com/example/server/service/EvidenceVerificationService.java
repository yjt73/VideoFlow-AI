package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class EvidenceVerificationService {

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null) return false;
        return context.segments().stream()
                .anyMatch(segment -> evidence.timestampMs() >= segment.startMs()
                        && evidence.timestampMs() < segment.endMs());
    }

    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return false;
        String source = evidence.source().toUpperCase(Locale.ROOT);
        if (!source.equals("ASR") && !source.equals("OCR")) return false;

        return context.segments().stream()
                .filter(segment -> evidence.timestampMs() >= segment.startMs()
                        && evidence.timestampMs() < segment.endMs())
                .anyMatch(segment -> {
                    String searchable = source.equals("OCR")
                            ? String.join(" ", segment.ocrTexts())
                            : segment.transcript();
                    return containsNormalized(searchable, evidence.content());
                });
    }

    public boolean supportsClaim(VideoContext context,
                                 String conclusion,
                                 AnalysisResult.Evidence evidence) {
        if (conclusion == null || conclusion.isBlank() || !supported(context, evidence)) {
            return false;
        }
        Set<String> evidenceTerms = terms(evidence.content() + " " + evidence.claim());
        Set<String> claimTerms = terms(conclusion);
        long matched = claimTerms.stream().filter(evidenceTerms::contains).count();
        boolean chineseMatch = claimTerms.stream()
                .filter(evidenceTerms::contains)
                .anyMatch(term -> term.codePoints()
                        .anyMatch(cp -> cp >= 0x4e00 && cp <= 0x9fff));
        return matched >= 2 || chineseMatch;
    }

    private boolean containsNormalized(String source, String expected) {
        return compact(source).contains(compact(expected));
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private String compact(String value) {
        return normalize(value).replace(" ", "");
    }

    private Set<String> terms(String value) {
        return Arrays.stream(normalize(value).split("[^\\p{L}\\p{N}\\p{IsHan}]+"))
                .filter(Predicate.not(String::isBlank))
                .filter(term -> term.length() > 1 || term.codePoints()
                        .anyMatch(cp -> cp >= 0x4e00 && cp <= 0x9fff))
                .collect(Collectors.toSet());
    }
}
