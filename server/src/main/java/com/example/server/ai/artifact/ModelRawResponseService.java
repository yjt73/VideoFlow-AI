package com.example.server.ai.artifact;

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
                INSERT INTO model_raw_responses
                (task_id, media_id, api_name, stage, model_version,
                 prompt_hash, response_text, parse_status, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId,
                mediaId,
                apiName,
                stage,
                modelVersion,
                Hashing.sha256(prompt),
                response,
                parseStatus,
                errorMessage
        );
    }
}
