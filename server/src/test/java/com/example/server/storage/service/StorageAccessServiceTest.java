package com.example.server.storage.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.config.S3StorageProperties;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageAccessServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final S3StorageProperties properties = new S3StorageProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            "media",
            "us-east-1",
            List.of("http://localhost:5173"),
            Duration.ofMinutes(10),
            Duration.ofMinutes(15)
    );
    private final StorageAccessService service = new StorageAccessService(
            mediaFileMapper,
            storageObjectMapper,
            gateway,
            properties
    );

    @Test
    void createsTenMinuteAccessUrlForOwner() {
        MediaFile media = media(3L);
        StorageObject object = activeObject();
        when(mediaFileMapper.selectById(9L)).thenReturn(media);
        when(storageObjectMapper.selectById(8L)).thenReturn(object);
        when(gateway.presignGet(object.getObjectKey(), Duration.ofMinutes(10)))
                .thenReturn(URI.create("http://localhost:9000/media/signed"));

        var view = service.accessUrl(9L, 3L);

        assertThat(view.url()).isEqualTo("http://localhost:9000/media/signed");
        verify(gateway).presignGet(object.getObjectKey(), Duration.ofMinutes(10));
    }

    @Test
    void rejectsAnotherUser() {
        when(mediaFileMapper.selectById(9L)).thenReturn(media(4L));

        assertThatThrownBy(() -> service.accessUrl(9L, 3L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void internalAccessUsesTheSamePrivateObjectResolution() {
        StorageObject object = activeObject();
        when(mediaFileMapper.selectById(9L)).thenReturn(media(3L));
        when(storageObjectMapper.selectById(8L)).thenReturn(object);
        when(gateway.presignGet(object.getObjectKey(), Duration.ofMinutes(10)))
                .thenReturn(URI.create("http://localhost:9000/media/internal"));

        assertThat(service.internalGetUrl(9L).toString())
                .isEqualTo("http://localhost:9000/media/internal");
    }

    private MediaFile media(Long userId) {
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(userId);
        media.setObjectId(8L);
        return media;
    }

    private StorageObject activeObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setObjectKey("media/01/0123456789abcdef0123456789abcdef");
        object.setStatus(StorageObjectStatus.ACTIVE);
        return object;
    }
}
