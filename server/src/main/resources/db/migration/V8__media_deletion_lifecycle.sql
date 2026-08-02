ALTER TABLE multipart_upload_sessions
    DROP FOREIGN KEY fk_multipart_sessions_media;

ALTER TABLE multipart_upload_sessions
    ADD CONSTRAINT fk_multipart_sessions_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE SET NULL;

ALTER TABLE analysis_tasks
    DROP FOREIGN KEY fk_analysis_tasks_media;

ALTER TABLE analysis_tasks
    ADD CONSTRAINT fk_analysis_tasks_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE video_qa_conversations
    DROP FOREIGN KEY fk_video_qa_conversations_media;

ALTER TABLE video_qa_conversations
    ADD CONSTRAINT fk_video_qa_conversations_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE rag_segments
    DROP FOREIGN KEY fk_rag_segments_media;

ALTER TABLE rag_segments
    ADD CONSTRAINT fk_rag_segments_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE video_qa_messages
    ADD CONSTRAINT fk_video_qa_messages_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE analysis_stage_checkpoints
    ADD CONSTRAINT fk_stage_checkpoints_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE analysis_artifacts
    ADD CONSTRAINT fk_analysis_artifacts_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;

ALTER TABLE model_raw_responses
    ADD CONSTRAINT fk_model_raw_responses_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE SET NULL;

ALTER TABLE analysis_quality_evaluations
    ADD CONSTRAINT fk_quality_evaluations_media
        FOREIGN KEY (media_id) REFERENCES media_files(id)
        ON DELETE CASCADE;
