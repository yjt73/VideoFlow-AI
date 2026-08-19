package com.example.server.service;

import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import com.example.server.service.S3MultipartGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageConsistencyAuditorTest {

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final StorageConsistencyAuditor auditor =
            new StorageConsistencyAuditor(storageObjectMapper, gateway, 500);

    @Test
    void reportsMissingDatabaseObjectAndOrphanWithoutDeletingAnything() {
        StorageObject object = activeObject();
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));
        when(gateway.exists(object.getObjectKey())).thenReturn(false);
        when(gateway.listKeys("media/")).thenReturn(List.of("media/ff/orphan"));
        when(storageObjectMapper.selectOne(any())).thenReturn(null);

        auditor.audit();

        verify(gateway, never()).delete(any());
    }

    @Test
    void logsRepeatedFindingWithoutUsingASeparateDatabaseTable() {
        StorageObject object = activeObject();
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));
        when(gateway.exists(object.getObjectKey())).thenReturn(false);
        when(gateway.listKeys("media/")).thenReturn(List.of());

        auditor.audit();

        verify(gateway, never()).delete(any());
    }

    private StorageObject activeObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setObjectKey("media/01/hash");
        object.setStatus(StorageObjectStatus.ACTIVE);
        return object;
    }
}
