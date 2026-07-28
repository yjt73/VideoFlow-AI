package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.dto.UploadCheckRequest;
import com.example.server.dto.UploadCheckResponse;
import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MediaServiceUploadTest {

    private static final String FILE_MD5 = "5d41402abc4b2a76b9719d911017c592";
    private static final String UPLOAD_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MinioUtils minioUtils;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private MediaService mediaService;

    @BeforeEach
    void setUpRedisOperations() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void returnsCompletedWhenCurrentUserAlreadyOwnsContent() {
        MediaFile existing = media(9L, 1L);
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        UploadCheckResponse response = mediaService.checkUpload(request(null), 1L);

        assertThat(response.state()).isEqualTo(UploadState.COMPLETED);
        assertThat(response.deduplicated()).isTrue();
        assertThat(response.media()).isSameAs(existing);
        assertThat(response.uploadId()).isNull();
    }

    @Test
    void resumesMatchingOwnedUpload() {
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(hashOperations.entries("upload:chunked:" + UPLOAD_ID)).thenReturn(Map.of(
                "filename", "demo.mp4",
                "fileMd5", FILE_MD5,
                "totalChunks", "3",
                "userId", "1",
                "status", "UPLOADING"
        ));
        when(setOperations.members("upload:chunked:" + UPLOAD_ID + ":parts"))
                .thenReturn(Set.of("0", "2"));

        UploadCheckResponse response = mediaService.checkUpload(request(UPLOAD_ID), 1L);

        assertThat(response.state()).isEqualTo(UploadState.UPLOADING);
        assertThat(response.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(response.uploadedChunks()).containsExactly(0, 2);
        assertThat(response.deduplicated()).isFalse();
    }

    @Test
    void rejectsExpiredExplicitUploadId() {
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(hashOperations.entries("upload:chunked:" + UPLOAD_ID)).thenReturn(Map.of());

        assertThatThrownBy(() -> mediaService.checkUpload(request(UPLOAD_ID), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void createsNewUploadWhenNothingCanBeResumed() {
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        UploadCheckResponse response = mediaService.checkUpload(request(null), 1L);

        assertThat(response.state()).isEqualTo(UploadState.NEW);
        assertThat(response.uploadId()).isNotBlank();
        assertThat(response.uploadedChunks()).isEmpty();
        verify(hashOperations).putAll(
                eq("upload:chunked:" + response.uploadId()),
                any(Map.class)
        );
    }

    private UploadCheckRequest request(String uploadId) {
        return new UploadCheckRequest("demo.mp4", FILE_MD5, 3, uploadId);
    }

    private MediaFile media(Long id, Long userId) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(userId);
        media.setFilename("demo.mp4");
        media.setContentHash(FILE_MD5);
        media.setStatus("COMPLETED");
        media.setFilePath("http://minio/demo.mp4");
        return media;
    }
}
