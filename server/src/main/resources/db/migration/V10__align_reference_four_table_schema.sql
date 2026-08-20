-- 将数据库收敛为 users、media_files、agent_checkpoints、failed_analysis_tasks 四张业务表。
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    media_id BIGINT NOT NULL,
    checkpoint_key VARCHAR(160) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    payload LONGTEXT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (media_id, checkpoint_key),
    KEY idx_agent_checkpoint_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS failed_analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
    content_hash VARCHAR(128) NOT NULL,
    user_goal VARCHAR(500) NOT NULL,
    attempt_count INT NOT NULL,
    error_type VARCHAR(128) NOT NULL,
    error_message VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'FAILED',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_failed_analysis_status_time (status, created_at),
    KEY idx_failed_analysis_media (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO failed_analysis_tasks (
    media_id, action, mode, content_hash, user_goal, attempt_count,
    error_type, error_message, status, created_at, updated_at
)
SELECT
    task.media_id,
    task.task_type,
    'GENERAL',
    COALESCE(media.content_hash, ''),
    COALESCE(task.user_goal, ''),
    GREATEST(task.retry_count, 1),
    COALESCE(task.failure_type, 'ANALYSIS_FAILED'),
    COALESCE(task.failure_message, task.error_message),
    CASE
        WHEN task.status IN ('DEAD', 'FAILED') THEN task.status
        ELSE 'FAILED'
    END,
    COALESCE(task.failure_recorded_at, task.updated_at, CURRENT_TIMESTAMP(3)),
    COALESCE(task.updated_at, CURRENT_TIMESTAMP(3))
FROM analysis_tasks task
JOIN media_files media ON media.id = task.media_id
WHERE task.failure_type IS NOT NULL
   OR task.failure_message IS NOT NULL
   OR task.error_message IS NOT NULL;

INSERT INTO agent_checkpoints (media_id, checkpoint_key, stage, payload)
SELECT
    artifact.media_id,
    CONCAT('migrated:artifact:', artifact.id),
    COALESCE(artifact.checkpoint_stage, artifact.stage),
    artifact.content_json
FROM analysis_artifacts artifact
WHERE artifact.checkpoint_stage IS NOT NULL
   OR artifact.artifact_type NOT IN ('MODEL_RAW_RESPONSE', 'QUALITY_EVALUATION');

ALTER TABLE media_files
    DROP FOREIGN KEY fk_media_storage_object;

ALTER TABLE media_files
    DROP INDEX idx_media_object_id;

ALTER TABLE media_files
    DROP COLUMN object_id;

ALTER TABLE media_files
    DROP INDEX uk_media_user_content_hash;

ALTER TABLE media_files
    MODIFY COLUMN content_hash VARCHAR(64) NULL;

CREATE INDEX idx_media_content_hash ON media_files(content_hash);

-- 保留旧表作为可回滚归档，应用不再访问这些表。
ALTER TABLE video_qa_messages RENAME TO legacy_video_qa_messages;
ALTER TABLE rag_segments RENAME TO legacy_rag_segments;
ALTER TABLE analysis_artifacts RENAME TO legacy_analysis_artifacts;
ALTER TABLE analysis_tasks RENAME TO legacy_analysis_tasks;
ALTER TABLE outbox_events RENAME TO legacy_outbox_events;
ALTER TABLE multipart_upload_sessions RENAME TO legacy_multipart_upload_sessions;
ALTER TABLE storage_objects RENAME TO legacy_storage_objects;
