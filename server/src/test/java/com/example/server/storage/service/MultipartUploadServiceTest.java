package com.example.server.storage.service;

import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.storage.api.MultipartCheckRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartUploadServiceTest {

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final MultipartUploadSessionMapper sessionMapper = mock(MultipartUploadSessionMapper.class);
    private final MediaReferenceService mediaReferenceService = mock(MediaReferenceService.class);
    private final StorageObjectKeyFactory keyFactory = new StorageObjectKeyFactory();
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final MultipartUploadService service = new MultipartUploadService(
            storageObjectMapper,
            sessionMapper,
            mediaReferenceService,
            keyFactory,
            gateway,
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
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(gateway.presignPart(
                session.getTemporaryObjectKey(),
                session.getProviderUploadId(),
                2,
                Duration.ofMinutes(15)
        )).thenReturn(URI.create("http://localhost:9000/media?partNumber=2"));

        var result = service.presignPart("session-1", 2, 3L);

        assertThat(result.partNumber()).isEqualTo(2);
        assertThat(result.url()).contains("partNumber=2");
    }

    @Test
    void rejectsAnotherUsersSession() {
        when(sessionMapper.selectOne(any())).thenReturn(activeSession(4L));

        assertThatThrownBy(() -> service.presignPart("session-1", 2, 3L))
                .isInstanceOf(AccessDeniedException.class);
    }

    private MultipartCheckRequest request() {
        return new MultipartCheckRequest(
                "课程.mp4",
                "0123456789abcdef0123456789abcdef",
                6L * 1024 * 1024,
                "video/mp4",
                2
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
        session.setUserId(userId);
        session.setStatus(MultipartUploadStatus.INITIATED);
        session.setTotalParts(2);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }
}
