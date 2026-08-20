package com.example.server.dto;

import java.util.Locale;

public enum AnalysisMode {
    GENERAL,
    LEARNING,
    REVIEW,
    CREATION;

    public static AnalysisMode fromNullable(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return GENERAL;
        }
    }

    public static AnalysisMode fromRequest(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的分析模式: " + value, exception);
        }
    }
}
