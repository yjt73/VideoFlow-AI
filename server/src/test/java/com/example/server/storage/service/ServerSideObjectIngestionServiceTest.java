package com.example.server.storage.service;

import com.example.server.api.DependencyUnavailableException;
import com.example.server.entity.MediaFile;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerSideObjectIngestionServiceTest {

    @TempDir
    Path tempDir;

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final MediaReferenceService mediaReferenceService = mock(MediaReferenceService.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final ServerSideObjectIngestionService service =
            new ServerSideObjectIngestionService(
                    storageObjectMapper,
                    mediaReferenceService,
                    new StorageObjectKeyFactory(),
                    gateway,
                    "media"
            );

    @Test
    void uploadsFileToCanonicalHashKeyAndCreatesReference() throws Exception {
        Path file = writeHello();
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            StorageObject object = invocation.getArgument(0);
            object.setId(8L);
            return 1;
        }).when(storageObjectMapper).insert(any(StorageObject.class));
        MediaFile media = new MediaFile();
        media.setId(9L);
        when(mediaReferenceService.createReference(any(), any(), any())).thenReturn(media);

        MediaFile result = service.ingest(file.toFile(), "demo.mp4", "video/mp4", 3L);

        verify(gateway).put(
                "media/5d/5d41402abc4b2a76b9719d911017c592",
                file,
                "video/mp4"
        );
        assertThat(result).isSameAs(media);
    }

    @Test
    void reusesExistingContentWithoutUploadingAgain() throws Exception {
        Path file = writeHello();
        StorageObject existing = new StorageObject();
        existing.setId(8L);
        when(storageObjectMapper.selectOne(any())).thenReturn(existing);
        MediaFile media = new MediaFile();
        when(mediaReferenceService.createReference(existing, 3L, "demo.mp4"))
                .thenReturn(media);

        assertThat(service.ingest(file.toFile(), "demo.mp4", "video/mp4", 3L))
                .isSameAs(media);
        verify(gateway, never()).put(any(), any(), any());
    }

    @Test
    void storageFailureDoesNotCreateDatabaseReference() throws Exception {
        Path file = writeHello();
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        doThrow(new DependencyUnavailableException("unavailable"))
                .when(gateway).put(any(), any(), any());

        assertThatThrownBy(() ->
                service.ingest(file.toFile(), "demo.mp4", "video/mp4", 3L)
        ).isInstanceOf(DependencyUnavailableException.class);

        verify(storageObjectMapper, never()).insert(any(StorageObject.class));
        verify(mediaReferenceService, never()).createReference(any(), any(), any());
    }

    private Path writeHello() throws Exception {
        Path file = tempDir.resolve("demo.mp4");
        Files.writeString(file, "hello");
        return file;
    }
}
