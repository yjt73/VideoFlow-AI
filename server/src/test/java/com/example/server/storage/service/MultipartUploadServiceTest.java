package com.example.server.storage.service;

import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.storage.api.MultipartCheckRequest;
import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.api.MultipartProgressRequest;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MultipartUploadServiceTest {

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
    private final MediaReferenceService mediaReferenceService = mock(MediaReferenceService.class);
    private final StorageObjectKeyFactory keyFactory = new StorageObjectKeyFactory();
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final MultipartUploadSessionCache cache = mock(MultipartUploadSessionCache.class);
    private final MultipartUploadService service = new MultipartUploadService(
            storageObjectMapper,
            sessionMapper,
            mediaReferenceService,
            keyFactory,
            gateway,
            cache,
            Duration.ofMinutes(15),
            "media"
    );

    @Test
    void returnsCompletedAndCreatesReferenceForExistingActiveObject() {
        StorageObject object = activeObject();
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(mediaReferenceService.findOwnedByHash(3L, request().contentHash()))
                .thenReturn(java.util.Optional.empty());
        when(storageObjectMapper.selectOne(any())).thenReturn(object);
        when(mediaReferenceService.createReference(object, 3L, request().filename()))
                .thenReturn(media);

        var result = service.check(request(), 3L);

        assertThat(result.state()).isEqualTo(UploadState.COMPLETED);
        assertThat(result.media()).isSameAs(media);
    }

    @Test
    void createsAndPersistsNativeMultipartSessionWhenContentIsNew() {
        when(mediaReferenceService.findOwnedByHash(3L, request().contentHash()))
                .thenReturn(java.util.Optional.empty());
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(gateway.create(any(), eq("video/mp4"))).thenReturn("provider-upload-1");

        var result = service.check(request(), 3L);

        ArgumentCaptor<MultipartUploadSession> captor =
                ArgumentCaptor.forClass(MultipartUploadSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getProviderUploadId()).isEqualTo("provider-upload-1");
        assertThat(captor.getValue().getStatus()).isEqualTo(MultipartUploadStatus.INITIATED);
        assertThat(result.state()).isEqualTo(UploadState.UPLOADING);
        assertThat(result.partSize()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void presignsOnlyAnOwnedActivePart() {
        MultipartUploadSession session = activeSession(3L);
        when(cache.findSession("session-1"))
                .thenReturn(Optional.of(MultipartSessionSnapshot.from(session)));
        when(gateway.presignPart(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId(),
                2,
                Duration.ofMinutes(15)
        )).thenReturn(URI.create("http://localhost:9000/media?partNumber=2"));

        var result = service.presignPart("session-1", 2, 3L);

        assertThat(result.partNumber()).isEqualTo(2);
        assertThat(result.url()).contains("partNumber=2");
        verify(sessionMapper, never()).selectOne(any());
    }

    @Test
    void rejectsAnotherUsersSession() {
        when(cache.findSession("session-1"))
                .thenReturn(Optional.of(MultipartSessionSnapshot.from(activeSession(4L))));

        assertThatThrownBy(() -> service.presignPart("session-1", 2, 3L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void returnsCachedProgressForExistingSessionWithoutSessionDatabaseQuery() {
        MultipartUploadSession session = activeSession(3L);
        List<CompletedPartRequest> parts = List.of(
                new CompletedPartRequest(1, "etag-1")
        );
        when(mediaReferenceService.findOwnedByHash(3L, request("session-1").contentHash()))
                .thenReturn(Optional.empty());
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        when(cache.findSession("session-1"))
                .thenReturn(Optional.of(MultipartSessionSnapshot.from(session)));
        when(cache.findParts("session-1")).thenReturn(Optional.of(parts));

        var result = service.check(request("session-1"), 3L);

        assertThat(result.completedParts()).containsExactlyElementsOf(parts);
        verify(sessionMapper, never()).selectOne(any());
        verify(gateway, never()).listParts(any(), any());
    }

    @Test
    void rebuildsProgressFromObjectStorageWhenRedisMisses() {
        MultipartUploadSession session = activeSession(3L);
        List<CompletedPartRequest> parts = List.of(
                new CompletedPartRequest(1, "etag-1")
        );
        when(mediaReferenceService.findOwnedByHash(3L, request().contentHash()))
                .thenReturn(Optional.empty());
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(gateway.listParts(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId()
        )).thenReturn(parts);

        var result = service.check(request(), 3L);

        assertThat(result.completedParts()).containsExactlyElementsOf(parts);
        verify(cache).putSession(MultipartSessionSnapshot.from(session));
        verify(cache).putParts("session-1", parts, session.getExpiresAt());
    }

    @Test
    void recordsCompletedPartsInRedis() {
        MultipartUploadSession session = activeSession(3L);
        List<CompletedPartRequest> parts = List.of(
                new CompletedPartRequest(1, "etag-1")
        );
        when(cache.findSession("session-1"))
                .thenReturn(Optional.of(MultipartSessionSnapshot.from(session)));

        service.recordProgress(
                "session-1",
                new MultipartProgressRequest(parts),
                3L
        );

        verify(cache).putParts("session-1", parts, session.getExpiresAt());
        verify(sessionMapper, never()).selectOne(any());
    }

    private MultipartCheckRequest request() {
        return request(null);
    }

    private MultipartCheckRequest request(String sessionId) {
        return new MultipartCheckRequest(
                "课程.mp4",
                "0123456789abcdef0123456789abcdef",
                6L * 1024 * 1024,
                "video/mp4",
                2,
                sessionId
        );
    }

    private StorageObject activeObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setContentHash(request().contentHash());
        object.setStatus(StorageObjectStatus.ACTIVE);
        return object;
    }

    private MultipartUploadSession activeSession(Long userId) {
        MultipartUploadSession session = new MultipartUploadSession();
        session.setSessionId("session-1");
        session.setProviderUploadId("provider-upload-1");
        session.setTemporaryObjectKey("temporary/3/session-1/course.mp4");
        session.setContentHash(request().contentHash());
        session.setUserId(userId);
        session.setStatus(MultipartUploadStatus.INITIATED);
        session.setTotalParts(2);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }
}
