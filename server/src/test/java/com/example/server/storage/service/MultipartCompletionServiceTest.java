package com.example.server.storage.service;

import com.example.server.api.BusinessException;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartCompletionServiceTest {

    private final MultipartUploadSessionMapper sessionMapper =
            mock(MultipartUploadSessionMapper.class);
    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final MediaReferenceService mediaReferenceService = mock(MediaReferenceService.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final MultipartUploadSessionCache cache = mock(MultipartUploadSessionCache.class);
    private final MultipartCompletionService service = new MultipartCompletionService(
            sessionMapper,
            storageObjectMapper,
            mediaFileMapper,
            mediaReferenceService,
            new StorageObjectKeyFactory(),
            gateway,
            new ObjectIntegrityVerifier(),
            cache
    );

    MultipartCompletionServiceTest() {
        when(sessionMapper.markCompleted(anyString(), anyLong())).thenReturn(1);
        when(sessionMapper.markAborted(anyString())).thenReturn(1);
        when(gateway.listParts(anyString(), anyString())).thenReturn(validParts());
    }

    @Test
    void completesVerifiesAndCreatesCanonicalObjectReference() {
        MultipartUploadSession session = initiatedSession(3L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.claimCompleting("session-1", 3L)).thenReturn(1);
        when(gateway.open(session.getTemporaryObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            StorageObject object = invocation.getArgument(0);
            object.setId(8L);
            return 1;
        }).when(storageObjectMapper).insert(any(StorageObject.class));
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(mediaReferenceService.createReference(any(StorageObject.class), any(), any()))
                .thenReturn(media);

        MediaFile result = service.complete("session-1", 3L);

        ArgumentCaptor<StorageObject> objectCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(gateway).complete(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId(),
                validParts()
        );
        verify(storageObjectMapper).insert(objectCaptor.capture());
        assertThat(objectCaptor.getValue().getObjectKey())
                .isEqualTo("media/5d/5d41402abc4b2a76b9719d911017c592");
        verify(sessionMapper).markCompleted("session-1", 9L);
        verify(gateway).delete(session.getTemporaryObjectKey());
        verify(cache).evict("session-1");
        assertThat(result).isSameAs(media);
    }

    @Test
    void rejectsUnsortedPartsBeforeCallingStorage() {
        List<CompletedPartRequest> parts = List.of(
                new CompletedPartRequest(2, "etag-2"),
                new CompletedPartRequest(1, "etag-1")
        );
        when(sessionMapper.selectOne(any())).thenReturn(initiatedSession(3L));
        when(sessionMapper.claimCompleting("session-1", 3L)).thenReturn(1);
        when(gateway.listParts(anyString(), anyString())).thenReturn(parts);

        assertThatThrownBy(() -> service.complete("session-1", 3L))
                .isInstanceOf(BusinessException.class);
        verify(gateway, never()).complete(any(), any(), any());
    }

    @Test
    void completesWithAuthoritativeObjectStorageEtags() {
        MultipartUploadSession session = initiatedSession(3L);
        List<CompletedPartRequest> actualParts = List.of(
                new CompletedPartRequest(1, "actual-1"),
                new CompletedPartRequest(2, "actual-2")
        );
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.claimCompleting("session-1", 3L)).thenReturn(1);
        when(gateway.listParts(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId()
        )).thenReturn(actualParts);
        when(gateway.open(session.getTemporaryObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        StorageObject existing = new StorageObject();
        existing.setId(8L);
        when(storageObjectMapper.selectOne(any())).thenReturn(existing);
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(mediaReferenceService.createReference(existing, 3L, "course.mp4"))
                .thenReturn(media);

        service.complete("session-1", 3L);

        verify(gateway).complete(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId(),
                actualParts
        );
    }

    @Test
    void deletesTemporaryObjectAndFailsSessionWhenIntegrityDoesNotMatch() {
        MultipartUploadSession session = initiatedSession(3L);
        session.setContentHash("0123456789abcdef0123456789abcdef");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.claimCompleting("session-1", 3L)).thenReturn(1);
        when(gateway.open(session.getTemporaryObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.complete("session-1", 3L))
                .isInstanceOf(BusinessException.class);

        verify(gateway).delete(session.getTemporaryObjectKey());
        verify(sessionMapper).markFailed("session-1", "对象完整性校验失败");
    }

    @Test
    void reusesObjectCreatedByConcurrentCompletion() {
        MultipartUploadSession session = initiatedSession(3L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.claimCompleting("session-1", 3L)).thenReturn(1);
        when(gateway.open(session.getTemporaryObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        StorageObject existing = new StorageObject();
        existing.setId(8L);
        when(storageObjectMapper.selectOne(any())).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("concurrent"))
                .when(storageObjectMapper).insert(any(StorageObject.class));
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(mediaReferenceService.createReference(existing, 3L, "course.mp4"))
                .thenReturn(media);

        assertThat(service.complete("session-1", 3L)).isSameAs(media);
    }

    @Test
    void completedRequestReturnsTheSameMedia() {
        MultipartUploadSession session = initiatedSession(3L);
        session.setStatus(MultipartUploadStatus.COMPLETED);
        session.setMediaId(9L);
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(mediaFileMapper.selectById(9L)).thenReturn(media);

        assertThat(service.complete("session-1", 3L)).isSameAs(media);
        verify(gateway, never()).complete(any(), any(), any());
    }

    @Test
    void abortIsIdempotentAndOwnerOnly() {
        MultipartUploadSession session = initiatedSession(3L);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        service.abort("session-1", 3L);

        verify(gateway).abort(session.getTemporaryObjectKey(), session.getProviderUploadId());
        verify(sessionMapper).markAborted("session-1");
        verify(cache).evict("session-1");

        session.setStatus(MultipartUploadStatus.ABORTED);
        service.abort("session-1", 3L);

        session.setUserId(4L);
        assertThatThrownBy(() -> service.abort("session-1", 3L))
                .isInstanceOf(AccessDeniedException.class);
    }

    private MultipartUploadSession initiatedSession(Long userId) {
        MultipartUploadSession session = new MultipartUploadSession();
        session.setSessionId("session-1");
        session.setProviderUploadId("provider-1");
        session.setUserId(userId);
        session.setFilename("course.mp4");
        session.setContentHash("5d41402abc4b2a76b9719d911017c592");
        session.setContentType("video/mp4");
        session.setSizeBytes(5L);
        session.setBucketName("media");
        session.setTemporaryObjectKey("temporary/3/session-1/course.mp4");
        session.setTotalParts(2);
        session.setStatus(MultipartUploadStatus.INITIATED);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private List<CompletedPartRequest> validParts() {
        return List.of(
                new CompletedPartRequest(1, "etag-1"),
                new CompletedPartRequest(2, "etag-2")
        );
    }
}
