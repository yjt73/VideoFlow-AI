package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AnalysisCheckpointService {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisCheckpointService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void markCompleted(CheckpointKey key, long artifactId, String checksum) {
        jdbcTemplate.update("""
                INSERT INTO analysis_stage_checkpoints
                (task_id, media_id, task_type, stage, goal_hash, input_hash,
                 model_version, artifact_id, artifact_checksum, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED')
                """,
                key.taskId(),
                key.mediaId(),
                key.taskType(),
                key.stage(),
                key.goalHash(),
                key.inputHash(),
                key.modelVersion(),
                artifactId,
                checksum
        );
    }

    public Optional<Long> findCompletedArtifact(CheckpointKey key) {
        return jdbcTemplate.query("""
                SELECT artifact_id
                FROM analysis_stage_checkpoints
                WHERE media_id = ?
                  AND task_type = ?
                  AND stage = ?
                  AND goal_hash = ?
                  AND input_hash = ?
                  AND model_version = ?
                  AND status = 'COMPLETED'
                ORDER BY id DESC
                LIMIT 1
                """,
                rs -> rs.next() ? Optional.of(rs.getLong("artifact_id")) : Optional.empty(),
                key.mediaId(),
                key.taskType(),
                key.stage(),
                key.goalHash(),
                key.inputHash(),
                key.modelVersion()
        );
    }
}
