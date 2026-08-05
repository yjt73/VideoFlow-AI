package com.example.server.storage.service;

import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.domain.MultipartUploadStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartUploadSessionCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations =
            mock(HashOperations.class);
    private final MultipartUploadSessionCache cache =
            new MultipartUploadSessionCache(redisTemplate);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void storesAndReadsSessionSnapshot() {
        MultipartSessionSnapshot snapshot = snapshot();
        when(hashOperations.entries("upload:multipart:session:session-1"))
                .thenReturn(Map.of(
                        "providerUploadId", "provider-1",
                        "userId", "3",
                        "contentHash", "0123456789abcdef0123456789abcdef",
                        "temporaryObjectKey", "temporary/3/session-1/course.mp4",
                        "totalParts", "2",
                        "expiresAt", snapshot.expiresAt().toString(),
                        "status", "INITIATED"
                ));

        cache.putSession(snapshot);

        verify(hashOperations).putAll(anyString(), any(Map.class));
        assertThat(cache.findSession("session-1")).contains(snapshot);
    }

    @Test
    void storesAndReadsCompletedParts() {
        when(hashOperations.entries("upload:multipart:parts:session-1"))
                .thenReturn(Map.of("1", "etag-1", "2", "etag-2"));

        cache.putParts("session-1", List.of(
                new CompletedPartRequest(1, "etag-1"),
                new CompletedPartRequest(2, "etag-2")
        ), snapshot().expiresAt());

        assertThat(cache.findParts("session-1")).hasValueSatisfying(parts ->
                assertThat(parts).containsExactly(
                        new CompletedPartRequest(1, "etag-1"),
                        new CompletedPartRequest(2, "etag-2")
                ));
    }

    @Test
    void redisFailureBehavesLikeCacheMiss() {
        when(hashOperations.entries(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.findSession("session-1")).isEmpty();
        assertThat(cache.findParts("session-1")).isEmpty();
    }

    private MultipartSessionSnapshot snapshot() {
        return new MultipartSessionSnapshot(
                "session-1",
                "provider-1",
                3L,
                "0123456789abcdef0123456789abcdef",
                "temporary/3/session-1/course.mp4",
                2,
                LocalDateTime.now().plusHours(1),
                MultipartUploadStatus.INITIATED
        );
    }
}
