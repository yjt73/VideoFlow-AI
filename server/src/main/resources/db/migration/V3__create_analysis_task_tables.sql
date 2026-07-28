CREATE TABLE analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    media_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    compensation_count INT NOT NULL DEFAULT 0,
    progress INT NOT NULL DEFAULT 0,
    current_stage VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    result_location VARCHAR(1000) NULL,
    user_goal VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_tasks_media FOREIGN KEY (media_id) REFERENCES media_files(id),
    CONSTRAINT fk_analysis_tasks_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_analysis_tasks_status_created (status, created_at),
    INDEX idx_analysis_tasks_media_type (media_id, task_type),
    INDEX idx_analysis_tasks_user_created (user_id, created_at)
);

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    publish_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_outbox_status_next_attempt (status, next_attempt_at)
);

CREATE TABLE analysis_task_failures (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    message_id VARCHAR(128) NULL,
    failure_type VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    stack_trace TEXT NULL,
    raw_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_failures_task_created (task_id, created_at)
);

CREATE UNIQUE INDEX uk_analysis_tasks_task_id
    ON analysis_tasks (task_id);

CREATE UNIQUE INDEX uk_outbox_event_id
    ON outbox_events (event_id);

CREATE UNIQUE INDEX uk_outbox_aggregate_event
    ON outbox_events (aggregate_id, event_type);
