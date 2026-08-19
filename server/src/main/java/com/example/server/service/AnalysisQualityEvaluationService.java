package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AnalysisQualityEvaluationService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisQualityEvaluationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(AnalysisQualityEvaluationRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            jdbcTemplate.update("""
                    INSERT INTO analysis_artifacts
                    (task_id, media_id, artifact_type, stage, content_hash, content_json)
                    VALUES (?, ?, 'QUALITY_EVALUATION', 'QUALITY_REVIEW', ?, ?)
                    """,
                    record.taskId(), record.mediaId(), Hashing.sha256(json), json
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("质量评估结果序列化失败", exception);
        }
    }
}
