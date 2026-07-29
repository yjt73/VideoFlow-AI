package com.example.server.storage.maintenance;

import com.example.server.storage.domain.StorageConsistencyFinding;
import com.example.server.storage.domain.StorageFindingType;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageConsistencyFindingMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import com.example.server.storage.service.S3MultipartGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageConsistencyAuditorTest {

    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final StorageConsistencyFindingMapper findingMapper =
            mock(StorageConsistencyFindingMapper.class);
    private final S3MultipartGateway gateway = mock(S3MultipartGateway.class);
    private final StorageConsistencyAuditor auditor =
            new StorageConsistencyAuditor(storageObjectMapper, findingMapper, gateway, 500);

    @Test
    void reportsMissingDatabaseObjectAndOrphanWithoutDeletingAnything() {
        StorageObject object = activeObject();
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));
        when(gateway.exists(object.getObjectKey())).thenReturn(false);
        when(gateway.listKeys("media/")).thenReturn(List.of("media/ff/orphan"));
        when(storageObjectMapper.selectOne(any())).thenReturn(null);
        when(findingMapper.selectOne(any())).thenReturn(null);

        auditor.audit();

        ArgumentCaptor<StorageConsistencyFinding> captor =
                ArgumentCaptor.forClass(StorageConsistencyFinding.class);
        verify(findingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(StorageConsistencyFinding::getFindingType)
                .containsExactlyInAnyOrder(
                        StorageFindingType.DATABASE_OBJECT_MISSING,
                        StorageFindingType.ORPHAN_OBJECT
                );
        verify(gateway, never()).delete(any());
    }

    @Test
    void updatesRepeatedFindingInsteadOfInsertingAgain() {
        StorageObject object = activeObject();
        StorageConsistencyFinding existing = new StorageConsistencyFinding();
        existing.setId(7L);
        existing.setFindingType(StorageFindingType.DATABASE_OBJECT_MISSING);
        existing.setObjectKey(object.getObjectKey());
        when(storageObjectMapper.selectList(any())).thenReturn(List.of(object));
        when(gateway.exists(object.getObjectKey())).thenReturn(false);
        when(gateway.listKeys("media/")).thenReturn(List.of());
        when(findingMapper.selectOne(any())).thenReturn(existing);

        auditor.audit();

        verify(findingMapper).updateById(existing);
        verify(findingMapper, never()).insert(any(StorageConsistencyFinding.class));
    }

    private StorageObject activeObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setObjectKey("media/01/hash");
        object.setStatus(StorageObjectStatus.ACTIVE);
        return object;
    }
}
