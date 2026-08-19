package com.example.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class EmbeddingCacheService {

    private static final TypeReference<List<Double>> EMBEDDING_TYPE =
            new TypeReference<>() {
            };

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<List<Double>> find(String text, String modelVersion) {
        String json = redisTemplate.opsForValue().get(key(text, modelVersion));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, EMBEDDING_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Embedding cache JSON cannot be read", exception);
        }
    }

    public void save(String text, String modelVersion, List<Double> embedding) {
        try {
            redisTemplate.opsForValue().set(
                    key(text, modelVersion),
                    objectMapper.writeValueAsString(embedding),
                    TTL
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Embedding cache JSON cannot be written", exception);
        }
    }

    private String key(String text, String modelVersion) {
        return "rag:embedding:" + modelVersion + ":" + Hashing.sha256(text);
    }
}
