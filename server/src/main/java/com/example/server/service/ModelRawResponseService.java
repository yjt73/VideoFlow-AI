package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ModelRawResponseService {

    private final JdbcTemplate jdbcTemplate;

    public ModelRawResponseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            String taskId,
            Long mediaId,
            String apiName,
            String stage,
            String modelVersion,
            String prompt,
            String response,
            String parseStatus,
            String errorMessage
    ) {
            jdbcTemplate.update("""
                    INSERT INTO analysis_artifacts
                    (task_id, media_id, artifact_type, stage, content_hash, content_json,
                     api_name, model_version, prompt_hash, parse_status, error_message)
                    VALUES (?, ?, 'MODEL_RAW_RESPONSE', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                taskId,
                mediaId,
                stage,
                Hashing.sha256(response),
                response,
                apiName,
                modelVersion,
                Hashing.sha256(prompt),
                parseStatus,
                errorMessage
        );
    }
}
