package com.example.server.dto;

public record RouteDecision(AnalysisMode mode, String reason) {
    public RouteDecision {
        mode = mode == null ? AnalysisMode.GENERAL : mode;
        reason = reason == null ? "" : reason.trim();
    }
}
