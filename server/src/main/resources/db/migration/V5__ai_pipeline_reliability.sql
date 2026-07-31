CREATE TABLE analysis_stage_checkpoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NULL,
    media_id BIGINT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    goal_hash VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    artifact_id BIGINT NULL,
    artifact_checksum VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_stage_checkpoint_resume (media_id, task_type, goal_hash, input_hash, model_version, stage, status),
    INDEX idx_stage_checkpoint_task (task_id, stage)
);

CREATE TABLE analysis_artifacts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_json LONGTEXT NOT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_analysis_artifacts_media_type (media_id, artifact_type, stage),
    INDEX idx_analysis_artifacts_hash (content_hash)
);

CREATE TABLE model_raw_responses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NULL,
    media_id BIGINT NULL,
    api_name VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    prompt_hash VARCHAR(64) NOT NULL,
    response_text LONGTEXT NOT NULL,
    parse_status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_model_raw_task_stage (task_id, stage),
    INDEX idx_model_raw_prompt (api_name, model_version, prompt_hash)
);

CREATE TABLE embedding_cache (
    id BIGINT NOT NULL AUTO_INCREMENT,
    text_hash VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    embedding_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_embedding_cache_text_model UNIQUE (text_hash, model_version)
);

CREATE TABLE analysis_quality_evaluations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NULL,
    media_id BIGINT NOT NULL,
    evaluator VARCHAR(128) NOT NULL,
    transcript_accuracy DECIMAL(5, 4) NULL,
    timestamp_accuracy DECIMAL(5, 4) NULL,
    conclusion_coverage DECIMAL(5, 4) NULL,
    hallucination_rate DECIMAL(5, 4) NULL,
    notes VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_quality_media (media_id, created_at),
    INDEX idx_quality_task (task_id)
);
