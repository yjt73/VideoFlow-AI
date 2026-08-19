package com.example.server.service;

import com.example.server.api.DependencyUnavailableException;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageObjectDeleteWorkerTest {

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final StorageObjectDeleteWorker worker =
            new StorageObjectDeleteWorker(storageObjectMapper, gateway);

    @Test
    void deletesObjectThenRemovesUnreferencedDatabaseRow() {
        StorageObject object = pendingObject();
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));

        worker.deletePendingObjects();

        verify(gateway).delete(object.getObjectKey());
        verify(storageObjectMapper).deleteIfUnreferenced(8L);
    }

    @Test
    void recordsFailureAndNextFixedBackoffTime() {
        StorageObject object = pendingObject();
        object.setDeleteAttempts(0);
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));
        doThrow(new DependencyUnavailableException("unavailable"))
                .when(gateway).delete(object.getObjectKey());

        LocalDateTime before = LocalDateTime.now().plusSeconds(59);
        worker.deletePendingObjects();

        ArgumentCaptor<LocalDateTime> retryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(storageObjectMapper).recordDeleteFailure(
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("unavailable"),
                retryCaptor.capture()
        );
        assertThat(retryCaptor.getValue()).isAfter(before);
        assertThat(retryCaptor.getValue()).isBefore(LocalDateTime.now().plusSeconds(62));
    }

    private StorageObject pendingObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setObjectKey("media/01/hash");
        object.setReferenceCount(0);
        object.setStatus(StorageObjectStatus.DELETE_PENDING);
        object.setDeleteAttempts(0);
        object.setNextDeleteAt(LocalDateTime.now().minusMinutes(1));
        return object;
    }
}
