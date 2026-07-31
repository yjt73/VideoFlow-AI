CREATE TABLE video_qa_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    title VARCHAR(200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_video_qa_conversations_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_video_qa_conversations_media FOREIGN KEY (media_id) REFERENCES media_files(id),
    CONSTRAINT uk_video_qa_conversation_id UNIQUE (conversation_id),
    INDEX idx_video_qa_conversations_user_media (user_id, media_id, updated_at)
);

CREATE TABLE video_qa_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    citations_json TEXT NULL,
    tool_calls_json TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_video_qa_messages_conversation_created (conversation_id, created_at),
    INDEX idx_video_qa_messages_user_media_created (user_id, media_id, created_at)
);
