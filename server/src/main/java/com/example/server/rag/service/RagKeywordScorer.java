package com.example.server.rag.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class RagKeywordScorer {

    public double score(String text, String question) {
        Set<String> textTerms = terms(text);
        Set<String> questionTerms = terms(question);
        if (textTerms.isEmpty() || questionTerms.isEmpty()) {
            return 0;
        }
        String normalizedText = normalize(text);
        double score = 0;
        for (String term : questionTerms) {
            if (textTerms.contains(term) || normalizedText.contains(term)) {
                score += 1.0 + Math.log(1.0 + term.length());
            }
        }
        return score / questionTerms.size();
    }

    Set<String> terms(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(normalize(value).split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .forEach(result::add);
        if (result.isEmpty() && value.trim().length() >= 2) {
            result.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim();
    }
}
