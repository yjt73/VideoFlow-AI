package com.example.server.service;

public record AgentCheckpointPayload(
        AgentCheckpointStage stage,
        String payload
) {
}
