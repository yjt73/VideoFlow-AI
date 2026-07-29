package com.example.server.storage.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageReferenceDeletionServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final StorageObjectMapper storageObjectMapper = mock(StorageObjectMapper.class);
    private final StorageReferenceDeletionService service =
            new StorageReferenceDeletionService(mediaFileMapper, storageObjectMapper);

    @Test
    void deletesMediaAndAtomicallyDecrementsSharedObjectReference() {
        MediaFile media = media(3L);
        when(mediaFileMapper.selectById(9L)).thenReturn(media);
        when(mediaFileMapper.deleteById(9L)).thenReturn(1);
        when(storageObjectMapper.decrementReference(8L)).thenReturn(1);

        service.deleteOwnedMedia(9L, 3L);

        verify(mediaFileMapper).deleteById(9L);
        verify(storageObjectMapper).decrementReference(8L);
    }

    @Test
    void rejectsDeletingAnotherUsersMedia() {
        when(mediaFileMapper.selectById(9L)).thenReturn(media(4L));

        assertThatThrownBy(() -> service.deleteOwnedMedia(9L, 3L))
                .isInstanceOf(AccessDeniedException.class);
        verify(mediaFileMapper, never()).deleteById(9L);
    }

    private MediaFile media(Long userId) {
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(userId);
        media.setObjectId(8L);
        return media;
    }
}
