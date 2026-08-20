package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.Optional;

@Service
@Profile("legacy-persistence")
public class AnalysisCheckpointService {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisCheckpointService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void markCompleted(CheckpointKey key, long artifactId, String checksum) {
        jdbcTemplate.update("""
                UPDATE analysis_artifacts
                SET task_id = ?,
                    task_type = ?,
                    checkpoint_stage = ?,
                    goal_hash = ?,
                    input_hash = ?,
                    model_version = ?,
                    artifact_checksum = ?,
                    checkpoint_status = 'COMPLETED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                key.taskId(),
                key.taskType(),
                key.stage(),
                key.goalHash(),
                key.inputHash(),
                key.modelVersion(),
                checksum,
                artifactId
        );
    }

    public Optional<Long> findCompletedArtifact(CheckpointKey key) {
        return jdbcTemplate.query("""
                SELECT id
                FROM analysis_artifacts
                WHERE media_id = ?
                  AND task_type = ?
                  AND checkpoint_stage = ?
                  AND goal_hash = ?
                  AND input_hash = ?
                  AND model_version = ?
                  AND checkpoint_status = 'COMPLETED'
                ORDER BY id DESC
                LIMIT 1
                """,
                rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty(),
                key.mediaId(),
                key.taskType(),
                key.stage(),
                key.goalHash(),
                key.inputHash(),
                key.modelVersion()
        );
    }
}
