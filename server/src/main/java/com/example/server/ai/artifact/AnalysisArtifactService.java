package com.example.server.ai.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalysisArtifactService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long save(Long mediaId, String artifactType, String stage, Object content) {
        String json = toJson(content);
        String hash = Hashing.sha256(json);
        jdbcTemplate.update("""
                INSERT INTO analysis_artifacts
                (media_id, artifact_type, stage, content_hash, content_json)
                VALUES (?, ?, ?, ?, ?)
                """, mediaId, artifactType, stage, hash, json);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    public <T> T read(long artifactId, Class<T> type) {
        String json = jdbcTemplate.queryForObject(
                "SELECT content_json FROM analysis_artifacts WHERE id = ?",
                String.class,
                artifactId
        );
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Artifact JSON cannot be read", exception);
        }
    }

    private String toJson(Object content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Artifact JSON cannot be written", exception);
        }
    }
}
