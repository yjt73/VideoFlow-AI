package com.example.server.storage.service;

import com.example.server.storage.domain.MultipartUploadStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class MultipartUploadSessionCache {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadSessionCache.class);
    private static final String SESSION_PREFIX = "upload:multipart:session:";
    private static final String PARTS_PREFIX = "upload:multipart:parts:";
    private static final Duration MAX_CACHE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public MultipartUploadSessionCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<MultipartSessionSnapshot> findSession(String sessionId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash()
                    .entries(sessionKey(sessionId));
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MultipartSessionSnapshot(
                    sessionId,
                    value(values, "providerUploadId"),
                    Long.valueOf(value(values, "userId")),
                    value(values, "contentHash"),
                    value(values, "temporaryObjectKey"),
                    Integer.parseInt(value(values, "totalParts")),
                    LocalDateTime.parse(value(values, "expiresAt")),
                    MultipartUploadStatus.valueOf(value(values, "status"))
            ));
        } catch (RuntimeException exception) {
            log.warn("读取分片上传会话缓存失败，回源数据库: sessionId={}, cause={}",
                    sessionId, exception.getMessage());
            return Optional.empty();
        }
    }

    public void putSession(MultipartSessionSnapshot snapshot) {
        Duration ttl = ttl(snapshot.expiresAt());
        if (ttl.isZero()) {
            return;
        }
        try {
            Map<String, String> values = new HashMap<>();
            values.put("providerUploadId", snapshot.providerUploadId());
            values.put("userId", snapshot.userId().toString());
            values.put("contentHash", snapshot.contentHash());
            values.put("temporaryObjectKey", snapshot.temporaryObjectKey());
            values.put("totalParts", Integer.toString(snapshot.totalParts()));
            values.put("expiresAt", snapshot.expiresAt().toString());
            values.put("status", snapshot.status().name());
            String key = sessionKey(snapshot.sessionId());
            redisTemplate.opsForHash().putAll(key, values);
            redisTemplate.expire(key, ttl);
        } catch (RuntimeException exception) {
            log.warn("写入分片上传会话缓存失败: sessionId={}, cause={}",
                    snapshot.sessionId(), exception.getMessage());
        }
    }

    public Optional<List<Integer>> findParts(String sessionId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(partsKey(sessionId));
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(values.entrySet()
                    .stream()
                    .filter(entry -> !"__initialized".equals(entry.getKey().toString()))
                    .map(entry -> Integer.parseInt(entry.getKey().toString()))
                    .sorted()
                    .toList());
        } catch (RuntimeException exception) {
            log.warn("读取分片上传进度缓存失败: sessionId={}, cause={}",
                    sessionId, exception.getMessage());
            return Optional.empty();
        }
    }

    public void putParts(
            String sessionId,
            List<Integer> parts,
            LocalDateTime expiresAt
    ) {
        Duration ttl = ttl(expiresAt);
        if (ttl.isZero()) {
            return;
        }
        try {
            Map<String, String> values = new HashMap<>();
            values.put("__initialized", "1");
            if (parts != null) {
                for (Integer partNumber : parts) {
                    values.put(Integer.toString(partNumber), "1");
                }
            }
            String key = partsKey(sessionId);
            redisTemplate.opsForHash().putAll(key, values);
            redisTemplate.expire(key, ttl);
        } catch (RuntimeException exception) {
            log.warn("写入分片上传进度缓存失败: sessionId={}, cause={}",
                    sessionId, exception.getMessage());
        }
    }

    public void evict(String sessionId) {
        try {
            redisTemplate.delete(List.of(sessionKey(sessionId), partsKey(sessionId)));
        } catch (RuntimeException exception) {
            log.warn("清理分片上传缓存失败: sessionId={}, cause={}",
                    sessionId, exception.getMessage());
        }
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("上传会话缓存字段缺失: " + key);
        }
        return value.toString();
    }

    private Duration ttl(LocalDateTime expiresAt) {
        Duration ttl = Duration.between(LocalDateTime.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ZERO;
        }
        return ttl.compareTo(MAX_CACHE_TTL) > 0 ? MAX_CACHE_TTL : ttl;
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String partsKey(String sessionId) {
        return PARTS_PREFIX + sessionId;
    }
}
