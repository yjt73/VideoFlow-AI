package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import com.example.server.dto.UploadCheckRequest;
import com.example.server.dto.UploadCheckResponse;
import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class MediaServiceUploadTest {

    private static final String FILE_MD5 = "5d41402abc4b2a76b9719d911017c592";
    private static final String UPLOAD_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CHUNK_UPLOAD_ID = "550e8400-e29b-41d4-a716-446655440001";

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MinioUtils minioUtils;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock mergeLock;
    @Mock
    private RLock chunkLock;
    @Mock
    private RReadWriteLock uploadLock;
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
        lenient().when(redissonClient.getReadWriteLock(any())).thenReturn(uploadLock);
        lenient().when(uploadLock.readLock()).thenReturn(chunkLock);
        lenient().when(uploadLock.writeLock()).thenReturn(mergeLock);
        lenient().when(chunkLock.tryLock()).thenReturn(true);
        lenient().when(chunkLock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(mergeLock.tryLock()).thenReturn(true);
        lenient().when(mergeLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @AfterEach
    void cleanTestUploadDirectory() throws Exception {
        for (String uploadId : List.of(UPLOAD_ID, CHUNK_UPLOAD_ID)) {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "dovideo-chunks", uploadId);
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // 测试清理失败不掩盖业务断言。
                        }
                    });
                }
            }
        }
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
                        .isEqualTo(ErrorCode.UPLOAD_SESSION_EXPIRED));
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

    @Test
    void storesChunkWhenHashMatches() throws Exception {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 2);
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        mediaService.uploadChunk(
                CHUNK_UPLOAD_ID,
                0,
                2,
                FILE_MD5,
                chunk,
                1L
        );

        Path stored = testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0");
        assertThat(Files.readString(stored)).isEqualTo("hello");
        verify(setOperations).add("upload:chunked:" + CHUNK_UPLOAD_ID + ":parts", "0");
    }

    @Test
    void rejectsChunkWhenHashDoesNotMatch() {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 2);
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        assertThatThrownBy(() -> mediaService.uploadChunk(
                CHUNK_UPLOAD_ID,
                0,
                2,
                "7d793037a0760186574b0282f2f435e7",
                chunk,
                1L
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).doesNotExist();
        verify(setOperations, never()).add(any(), any());
    }

    @Test
    void acceptsUploadingSameChunkTwice() throws Exception {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 2);
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        mediaService.uploadChunk(CHUNK_UPLOAD_ID, 0, 2, FILE_MD5, chunk, 1L);
        mediaService.uploadChunk(CHUNK_UPLOAD_ID, 0, 2, FILE_MD5, chunk, 1L);

        assertThat(Files.readString(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")))
                .isEqualTo("hello");
        verify(setOperations, times(2))
                .add("upload:chunked:" + CHUNK_UPLOAD_ID + ":parts", "0");
    }

    @Test
    void rejectsLateChunkWhenUploadIsMerging() {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 2, "MERGING");
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        assertThatThrownBy(() -> mediaService.uploadChunk(
                CHUNK_UPLOAD_ID, 0, 2, FILE_MD5, chunk, 1L
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).doesNotExist();
    }

    @Test
    void chunkCannotStartWhileMergeOwnsWriteLock() {
        when(chunkLock.tryLock()).thenReturn(false);
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        assertThatThrownBy(() -> mediaService.uploadChunk(
                CHUNK_UPLOAD_ID, 0, 2, FILE_MD5, chunk, 1L
        )).isInstanceOf(DependencyUnavailableException.class);

        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).doesNotExist();
    }

    @Test
    void rejectsChunkOutsideDeclaredRange() {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 2);
        MockMultipartFile chunk = new MockMultipartFile("file", "part", null, "hello".getBytes());

        assertThatThrownBy(() -> mediaService.uploadChunk(
                CHUNK_UPLOAD_ID,
                2,
                2,
                FILE_MD5,
                chunk,
                1L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMergeWhenWholeFileHashDiffers() throws Exception {
        stubMergeUpload("7d793037a0760186574b0282f2f435e7", "UPLOADING", null);
        writePart(CHUNK_UPLOAD_ID, 0, "hello");

        assertThatThrownBy(() -> mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        verify(minioUtils, never()).uploadLocalFile(any(), any());
        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).exists();
    }

    @Test
    void returnsSameMediaWhenUploadAlreadyCompleted() throws Exception {
        MediaFile existing = media(9L, 1L);
        stubMergeUpload(FILE_MD5, "COMPLETED", 9L);
        when(mediaFileMapper.selectById(9L)).thenReturn(existing);

        MediaFile result = mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L);

        assertThat(result).isSameAs(existing);
        verify(minioUtils, never()).uploadLocalFile(any(), any());
    }

    @Test
    void keepsChunksWhenMinioUploadFails() throws Exception {
        stubMergeUpload(FILE_MD5, "UPLOADING", null);
        writePart(CHUNK_UPLOAD_ID, 0, "hello");
        doThrow(new IllegalStateException("minio unavailable"))
                .when(minioUtils).uploadLocalFile(any(), eq("demo.mp4"));

        assertThatThrownBy(() -> mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L))
                .isInstanceOf(DependencyUnavailableException.class);

        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).exists();
    }

    @Test
    void returnsExistingMediaOnDuplicateKeyConflict() throws Exception {
        MediaFile existing = media(12L, 1L);
        stubMergeUpload(FILE_MD5, "UPLOADING", null);
        writePart(CHUNK_UPLOAD_ID, 0, "hello");
        when(minioUtils.uploadLocalFile(any(), eq("demo.mp4")))
                .thenReturn("http://minio/new-object.mp4");
        when(mediaFileMapper.insert(any(MediaFile.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        MediaFile result = mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L);

        assertThat(result).isSameAs(existing);
        verify(minioUtils).removeFile("http://minio/new-object.mp4");
    }

    @Test
    void onlyOneMergeCanOwnTheRedissonLock() {
        stubMergeUpload(FILE_MD5, "UPLOADING", null);
        when(mergeLock.tryLock()).thenReturn(false);

        assertThatThrownBy(() -> mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void successfulMergePersistsHashAndBecomesIdempotent() throws Exception {
        stubMergeUpload(FILE_MD5, "UPLOADING", null);
        writePart(CHUNK_UPLOAD_ID, 0, "hello");
        when(minioUtils.uploadLocalFile(any(), eq("demo.mp4")))
                .thenReturn("http://minio/demo.mp4");
        doAnswer(invocation -> {
            MediaFile inserted = invocation.getArgument(0);
            inserted.setId(21L);
            return 1;
        }).when(mediaFileMapper).insert(any(MediaFile.class));

        MediaFile result = mediaService.completeChunkedUpload(CHUNK_UPLOAD_ID, 1L);

        assertThat(result.getContentHash()).isEqualTo(FILE_MD5);
        assertThat(result.getId()).isEqualTo(21L);
        verify(hashOperations).putAll(
                "upload:chunked:" + CHUNK_UPLOAD_ID,
                Map.of("status", "COMPLETED", "mediaId", "21")
        );
        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID).resolve("part-0")).doesNotExist();
    }

    @Test
    void cancelRemovesSessionAndFiles() throws Exception {
        stubOwnedUpload(CHUNK_UPLOAD_ID, 1);
        writePart(CHUNK_UPLOAD_ID, 0, "hello");

        mediaService.cancelUpload(CHUNK_UPLOAD_ID, 1L);

        assertThat(testUploadDirectory(CHUNK_UPLOAD_ID)).doesNotExist();
        verify(redisTemplate).delete(List.of(
                "upload:chunked:" + CHUNK_UPLOAD_ID,
                "upload:chunked:" + CHUNK_UPLOAD_ID + ":parts"
        ));
    }

    @Test
    void cancellingMissingSessionIsIdempotent() {
        when(hashOperations.entries("upload:chunked:" + CHUNK_UPLOAD_ID)).thenReturn(Map.of());

        mediaService.cancelUpload(CHUNK_UPLOAD_ID, 1L);

        verify(redisTemplate).delete(List.of(
                "upload:chunked:" + CHUNK_UPLOAD_ID,
                "upload:chunked:" + CHUNK_UPLOAD_ID + ":parts"
        ));
    }

    @Test
    void cancelDoesNotRaceWithMergeOwner() {
        when(mergeLock.tryLock()).thenReturn(false);

        assertThatThrownBy(() -> mediaService.cancelUpload(CHUNK_UPLOAD_ID, 1L))
                .isInstanceOf(DependencyUnavailableException.class);

        verify(redisTemplate, never()).delete(any(Collection.class));
    }

    @Test
    void regularUploadReturnsExistingMediaOnDuplicateKeyConflict() {
        MediaFile existing = media(31L, 1L);
        when(mediaFileMapper.insert(any(MediaFile.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        MediaFile result = mediaService.saveUploadedMedia(
                "demo.mp4",
                "http://minio/new-object.mp4",
                FILE_MD5,
                1L
        );

        assertThat(result).isSameAs(existing);
        verify(minioUtils).removeFile("http://minio/new-object.mp4");
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

    private void stubOwnedUpload(String uploadId, int totalChunks) {
        stubOwnedUpload(uploadId, totalChunks, "UPLOADING");
    }

    private void stubOwnedUpload(String uploadId, int totalChunks, String status) {
        when(hashOperations.entries("upload:chunked:" + uploadId)).thenReturn(Map.of(
                "filename", "demo.mp4",
                "fileMd5", FILE_MD5,
                "totalChunks", String.valueOf(totalChunks),
                "userId", "1",
                "status", status
        ));
    }

    private void stubMergeUpload(String fileMd5, String status, Long mediaId) {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("filename", "demo.mp4");
        metadata.put("fileMd5", fileMd5);
        metadata.put("totalChunks", "1");
        metadata.put("userId", "1");
        metadata.put("status", status);
        if (mediaId != null) {
            metadata.put("mediaId", String.valueOf(mediaId));
        }
        when(hashOperations.entries("upload:chunked:" + CHUNK_UPLOAD_ID))
                .thenReturn((Map) metadata);
        lenient().when(setOperations.members("upload:chunked:" + CHUNK_UPLOAD_ID + ":parts"))
                .thenReturn(Set.of("0"));
    }

    private void writePart(String uploadId, int index, String content) throws Exception {
        Path directory = testUploadDirectory(uploadId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("part-" + index), content);
    }

    private Path testUploadDirectory(String uploadId) {
        return Path.of(System.getProperty("java.io.tmpdir"), "dovideo-chunks", uploadId);
    }
}
