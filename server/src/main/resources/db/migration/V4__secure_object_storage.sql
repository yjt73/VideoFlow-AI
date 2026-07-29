CREATE TABLE storage_objects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content_hash VARCHAR(32) NOT NULL,
    bucket_name VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    reference_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    delete_attempts INT NOT NULL DEFAULT 0,
    next_delete_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_storage_objects_hash UNIQUE (content_hash),
    CONSTRAINT uk_storage_objects_location UNIQUE (bucket_name, object_key),
    INDEX idx_storage_objects_delete (status, next_delete_at)
);

CREATE TABLE multipart_upload_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    provider_upload_id VARCHAR(512) NOT NULL,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_hash VARCHAR(32) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    bucket_name VARCHAR(128) NOT NULL,
    temporary_object_key VARCHAR(512) NOT NULL,
    total_parts INT NOT NULL,
    part_size BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    media_id BIGINT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_multipart_sessions_session_id UNIQUE (session_id),
    CONSTRAINT fk_multipart_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_multipart_sessions_media FOREIGN KEY (media_id) REFERENCES media_files(id),
    INDEX idx_multipart_sessions_resume (user_id, content_hash, status, expires_at),
    INDEX idx_multipart_sessions_expiry (status, expires_at)
);

CREATE TABLE storage_consistency_findings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    finding_type VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    storage_object_id BIGINT NULL,
    details VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL,
    first_detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_storage_findings_type_key UNIQUE (finding_type, object_key),
    CONSTRAINT fk_storage_findings_object
        FOREIGN KEY (storage_object_id) REFERENCES storage_objects(id),
    INDEX idx_storage_findings_status (status, last_detected_at)
);

ALTER TABLE media_files
    ADD COLUMN object_id BIGINT NULL;

ALTER TABLE media_files
    ADD CONSTRAINT fk_media_storage_object
        FOREIGN KEY (object_id) REFERENCES storage_objects(id);

CREATE INDEX idx_media_object_id ON media_files(object_id);
