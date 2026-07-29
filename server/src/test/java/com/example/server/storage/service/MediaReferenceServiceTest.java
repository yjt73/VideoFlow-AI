package com.example.server.storage.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaReferenceServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final MediaReferenceService service =
            new MediaReferenceService(mediaFileMapper, storageObjectMapper);

    @Test
    void createsMediaAndIncrementsObjectReference() {
        StorageObject storageObject = activeObject();
        when(mediaFileMapper.selectOne(any())).thenReturn(null);
        when(storageObjectMapper.incrementReference(8L)).thenReturn(1);

        MediaFile created = service.createReference(storageObject, 3L, "课程.mp4");

        ArgumentCaptor<MediaFile> mediaCaptor = ArgumentCaptor.forClass(MediaFile.class);
        verify(storageObjectMapper).incrementReference(8L);
        verify(mediaFileMapper).insert(mediaCaptor.capture());
        assertThat(mediaCaptor.getValue().getObjectId()).isEqualTo(8L);
        assertThat(mediaCaptor.getValue().getContentHash()).isEqualTo(storageObject.getContentHash());
        assertThat(mediaCaptor.getValue().getFilePath()).isEmpty();
        assertThat(created).isSameAs(mediaCaptor.getValue());
    }

    @Test
    void reusesExistingMediaWithoutIncreasingReferenceAgain() {
        MediaFile existing = new MediaFile();
        existing.setId(12L);
        when(mediaFileMapper.selectOne(any())).thenReturn(existing);

        MediaFile result = service.createReference(activeObject(), 3L, "课程.mp4");

        assertThat(result).isSameAs(existing);
        verify(storageObjectMapper, never()).incrementReference(any(Long.class));
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
    }

    private StorageObject activeObject() {
        StorageObject object = new StorageObject();
        object.setId(8L);
        object.setContentHash("0123456789abcdef0123456789abcdef");
        object.setStatus(StorageObjectStatus.ACTIVE);
        return object;
    }
}
