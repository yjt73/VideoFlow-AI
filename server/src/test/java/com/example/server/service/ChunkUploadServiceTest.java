package com.example.server.service;

import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkUploadServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void cancelRemovesOnlyUploadedPartsAndSessionMetadata() {
        String uploadId = UUID.randomUUID().toString();
        String key = "upload:chunked:" + uploadId;
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        MinioUtils minio = mock(MinioUtils.class);
        MediaService media = mock(MediaService.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForSet()).thenReturn(sets);
        when(redisson.getLock("lock:upload:merge:" + uploadId)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(hashes.entries(key)).thenReturn(Map.of("userId", "1", "totalChunks", "3"));
        when(sets.members(key + ":parts")).thenReturn(Set.of("0", "2"));

        new ChunkUploadService(redis, redisson, minio, media).cancel(uploadId, 1L);

        verify(minio).removeObject("chunk-uploads/" + uploadId + "/part-0");
        verify(minio).removeObject("chunk-uploads/" + uploadId + "/part-2");
        verify(minio, never()).removeObject("chunk-uploads/" + uploadId + "/part-1");
        verify(redis).delete(List.of(key, key + ":parts"));
        verify(lock).unlock();
    }
}
