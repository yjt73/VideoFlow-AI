package com.example.server.qa.persistence;

import com.example.server.service.VideoQaCitation;
import com.example.server.service.VideoQaToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Repository
@Profile("legacy-persistence")
public class VideoQaHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VideoQaHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void createConversation(String conversationId, Long userId, Long mediaId, String title) {
        jdbcTemplate.update("""
                INSERT INTO video_qa_messages
                    (conversation_id, user_id, media_id, role, content, conversation_title)
                SELECT ?, ?, ?, 'SYSTEM', ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM video_qa_messages
                    WHERE conversation_id = ? AND role = 'SYSTEM'
                )
                """, conversationId, userId, mediaId, title, title, conversationId);
    }

    public void saveMessage(String conversationId,
                            Long userId,
                            Long mediaId,
                            String role,
                            String content,
                            List<VideoQaCitation> citations,
                            List<VideoQaToolResult> toolCalls) {
        jdbcTemplate.update("""
                INSERT INTO video_qa_messages
                    (conversation_id, user_id, media_id, role, content,
                     citations_json, tool_calls_json, conversation_title)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """,
                conversationId,
                userId,
                mediaId,
                role,
                content,
                citations == null ? null : write(citations),
                toolCalls == null ? null : write(toolCalls)
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("问答历史序列化失败", e);
        }
    }
}
