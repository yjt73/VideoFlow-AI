ALTER TABLE media_files
    ADD COLUMN content_hash VARCHAR(32) NOT NULL;

CREATE UNIQUE INDEX uk_media_user_content_hash
    ON media_files (user_id, content_hash);
