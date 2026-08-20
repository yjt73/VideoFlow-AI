-- 将失败记录、检查点、模型原始响应和质量评估收敛到已有业务表。
ALTER TABLE analysis_tasks ADD COLUMN failure_type VARCHAR(32) NULL;
ALTER TABLE analysis_tasks ADD COLUMN failure_message VARCHAR(1000) NULL;
ALTER TABLE analysis_tasks ADD COLUMN failure_stack_trace TEXT NULL;
ALTER TABLE analysis_tasks ADD COLUMN failure_raw_message TEXT NULL;
ALTER TABLE analysis_tasks ADD COLUMN failure_recorded_at DATETIME NULL;

UPDATE analysis_tasks
SET failure_type = (
        SELECT failure_type
        FROM analysis_task_failures
        WHERE task_id = analysis_tasks.task_id
        ORDER BY id DESC
        LIMIT 1
    ),
    error_code = COALESCE(
        (
            SELECT error_code
            FROM analysis_task_failures
            WHERE task_id = analysis_tasks.task_id
            ORDER BY id DESC
            LIMIT 1
        ),
        error_code
    ),
    failure_message = (
        SELECT error_message
        FROM analysis_task_failures
        WHERE task_id = analysis_tasks.task_id
        ORDER BY id DESC
        LIMIT 1
    ),
    failure_stack_trace = (
        SELECT stack_trace
        FROM analysis_task_failures
        WHERE task_id = analysis_tasks.task_id
        ORDER BY id DESC
        LIMIT 1
    ),
    failure_raw_message = (
        SELECT raw_message
        FROM analysis_task_failures
        WHERE task_id = analysis_tasks.task_id
        ORDER BY id DESC
        LIMIT 1
    ),
    failure_recorded_at = (
        SELECT created_at
        FROM analysis_task_failures
        WHERE task_id = analysis_tasks.task_id
        ORDER BY id DESC
        LIMIT 1
    )
WHERE EXISTS (
    SELECT 1
    FROM analysis_task_failures
    WHERE task_id = analysis_tasks.task_id
);

ALTER TABLE analysis_artifacts ADD COLUMN task_id VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN task_type VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN checkpoint_stage VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN goal_hash VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN input_hash VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN model_version VARCHAR(128) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN artifact_checksum VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN checkpoint_status VARCHAR(32) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN api_name VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN prompt_hash VARCHAR(64) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN parse_status VARCHAR(32) NULL;
ALTER TABLE analysis_artifacts ADD COLUMN error_message VARCHAR(1000) NULL;

UPDATE analysis_artifacts
SET task_id = (
        SELECT task_id FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    task_type = (
        SELECT task_type FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    checkpoint_stage = (
        SELECT stage FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    goal_hash = (
        SELECT goal_hash FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    input_hash = (
        SELECT input_hash FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    model_version = (
        SELECT model_version FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    artifact_checksum = (
        SELECT artifact_checksum FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    ),
    checkpoint_status = (
        SELECT status FROM analysis_stage_checkpoints
        WHERE artifact_id = analysis_artifacts.id
        ORDER BY id DESC LIMIT 1
    )
WHERE id IN (
    SELECT artifact_id
    FROM analysis_stage_checkpoints
    WHERE artifact_id IS NOT NULL
);

INSERT INTO analysis_artifacts
    (task_id, media_id, artifact_type, stage, content_hash, content_json,
     api_name, model_version, prompt_hash, parse_status, error_message, created_at)
SELECT raw_response.task_id,
       COALESCE(raw_response.media_id, task.media_id),
       'MODEL_RAW_RESPONSE', raw_response.stage,
       'MIGRATED_RAW_RESPONSE', raw_response.response_text,
       raw_response.api_name, raw_response.model_version, raw_response.prompt_hash,
       raw_response.parse_status, raw_response.error_message, raw_response.created_at
FROM model_raw_responses raw_response
LEFT JOIN analysis_tasks task ON task.task_id = raw_response.task_id
WHERE COALESCE(raw_response.media_id, task.media_id) IS NOT NULL;

INSERT INTO analysis_artifacts
    (task_id, media_id, artifact_type, stage, content_hash, content_json, created_at)
SELECT task_id, media_id, 'QUALITY_EVALUATION', 'QUALITY_REVIEW',
       'MIGRATED_QUALITY_EVALUATION',
       CONCAT(
           '{"evaluator":"', COALESCE(evaluator, ''),
           '","transcriptAccuracy":"', COALESCE(transcript_accuracy, ''),
           '","timestampAccuracy":"', COALESCE(timestamp_accuracy, ''),
           '","conclusionCoverage":"', COALESCE(conclusion_coverage, ''),
           '","hallucinationRate":"', COALESCE(hallucination_rate, ''),
           '","notes":"', COALESCE(notes, ''), '"}'
       ),
       created_at
FROM analysis_quality_evaluations;

ALTER TABLE video_qa_messages ADD COLUMN conversation_title VARCHAR(200) NULL;

INSERT INTO video_qa_messages
    (conversation_id, user_id, media_id, role, content, conversation_title, created_at)
SELECT conversation_id, user_id, media_id, 'SYSTEM', COALESCE(title, ''), title, created_at
FROM video_qa_conversations conversation
WHERE NOT EXISTS (
    SELECT 1
    FROM video_qa_messages message
    WHERE message.conversation_id = conversation.conversation_id
      AND message.role = 'SYSTEM'
);

-- 保留旧表作为可回滚归档，应用不再访问这些表。
ALTER TABLE analysis_task_failures RENAME TO legacy_analysis_task_failures;
ALTER TABLE analysis_stage_checkpoints RENAME TO legacy_analysis_stage_checkpoints;
ALTER TABLE model_raw_responses RENAME TO legacy_model_raw_responses;
ALTER TABLE embedding_cache RENAME TO legacy_embedding_cache;
ALTER TABLE analysis_quality_evaluations RENAME TO legacy_analysis_quality_evaluations;
ALTER TABLE video_qa_conversations RENAME TO legacy_video_qa_conversations;
ALTER TABLE storage_consistency_findings RENAME TO legacy_storage_consistency_findings;
