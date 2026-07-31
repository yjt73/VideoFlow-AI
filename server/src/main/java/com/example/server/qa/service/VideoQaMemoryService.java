package com.example.server.qa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class VideoQaMemoryService {

    private static final int MAX_MESSAGES = 20;
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public VideoQaMemoryService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<VideoQaMemoryMessage> loadRecent(Long userId, Long mediaId, String conversationId) {
        List<String> values = redisTemplate.opsForList().range(key(userId, mediaId, conversationId), 0, -1);
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::read)
                .filter(Objects::nonNull)
                .toList();
    }

    public void appendPair(Long userId, Long mediaId, String conversationId, String question, String answer) {
        String key = key(userId, mediaId, conversationId);
        redisTemplate.opsForList().rightPush(key, write(new VideoQaMemoryMessage("USER", question)));
        redisTemplate.opsForList().rightPush(key, write(new VideoQaMemoryMessage("ASSISTANT", answer)));
        redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
        redisTemplate.expire(key, TTL);
    }

    private VideoQaMemoryMessage read(String json) {
        try {
            return objectMapper.readValue(json, VideoQaMemoryMessage.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String write(VideoQaMemoryMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话记忆序列化失败", e);
        }
    }

    private String key(Long userId, Long mediaId, String conversationId) {
        return "video:qa:memory:" + userId + ":" + mediaId + ":" + conversationId;
    }
}
