package com.example.server.service;

public enum AgentCheckpointStage {
    PLAN,
    EXECUTION,
    CRITIQUE,
    RESULT,
    FAILED;

    public static AgentCheckpointStage from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
