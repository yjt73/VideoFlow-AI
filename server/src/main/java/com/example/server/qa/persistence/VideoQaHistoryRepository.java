package com.example.server.qa.persistence;

import com.example.server.service.VideoQaCitation;
import com.example.server.service.VideoQaToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class VideoQaHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VideoQaHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void createConversation(String conversationId, Long userId, Long mediaId, String title) {
        jdbcTemplate.update("""
                INSERT INTO video_qa_conversations (conversation_id, user_id, media_id, title)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP
                """, conversationId, userId, mediaId, title);
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
                    (conversation_id, user_id, media_id, role, content, citations_json, tool_calls_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                conversationId,
                userId,
                mediaId,
                role,
                content,
                citations == null ? null : write(citations),
                toolCalls == null ? null : write(toolCalls)
        );
        jdbcTemplate.update("""
                UPDATE video_qa_conversations
                SET updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?
                """, conversationId);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("问答历史序列化失败", e);
        }
    }
}
