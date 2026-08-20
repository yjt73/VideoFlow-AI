package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;

import java.util.List;

public record ModeProfile(
        AnalysisMode mode,
        String displayName,
        String planInstruction,
        String executeInstruction,
        String criticInstruction,
        List<String> requiredSectionKeys
) {
    public ModeProfile {
        mode = mode == null ? AnalysisMode.GENERAL : mode;
        displayName = displayName == null ? "" : displayName.trim();
        planInstruction = planInstruction == null ? "" : planInstruction;
        executeInstruction = executeInstruction == null ? "" : executeInstruction;
        criticInstruction = criticInstruction == null ? "" : criticInstruction;
        requiredSectionKeys = requiredSectionKeys == null
                ? List.of()
                : requiredSectionKeys.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
