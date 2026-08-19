package com.example.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class EmbeddingCacheService {

    private static final TypeReference<List<Double>> EMBEDDING_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingCacheService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<List<Double>> find(String text, String modelVersion) {
        try {
            String json = jdbcTemplate.queryForObject("""
                    SELECT embedding_json
                    FROM embedding_cache
                    WHERE text_hash = ? AND model_version = ?
                    """, String.class, Hashing.sha256(text), modelVersion);
            return Optional.of(objectMapper.readValue(json, EMBEDDING_TYPE));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Embedding cache JSON cannot be read", exception);
        }
    }

    public void save(String text, String modelVersion, List<Double> embedding) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO embedding_cache (text_hash, model_version, embedding_json)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        embedding_json = VALUES(embedding_json),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    Hashing.sha256(text),
                    modelVersion,
                    objectMapper.writeValueAsString(embedding)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Embedding cache JSON cannot be written", exception);
        }
    }
}
