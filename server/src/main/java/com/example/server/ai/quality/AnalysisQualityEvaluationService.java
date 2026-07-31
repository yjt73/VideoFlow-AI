package com.example.server.ai.quality;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalysisQualityEvaluationService {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisQualityEvaluationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(AnalysisQualityEvaluationRecord record) {
        jdbcTemplate.update("""
                INSERT INTO analysis_quality_evaluations
                (task_id, media_id, evaluator, transcript_accuracy,
                 timestamp_accuracy, conclusion_coverage, hallucination_rate, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.taskId(),
                record.mediaId(),
                record.evaluator(),
                record.transcriptAccuracy(),
                record.timestampAccuracy(),
                record.conclusionCoverage(),
                record.hallucinationRate(),
                record.notes()
        );
    }
}
