package com.example.server.storage.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class StorageReferenceDeletionService {

    private final MediaFileMapper mediaFileMapper;
    private final StorageObjectMapper storageObjectMapper;

    public StorageReferenceDeletionService(
            MediaFileMapper mediaFileMapper,
            StorageObjectMapper storageObjectMapper
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.storageObjectMapper = storageObjectMapper;
    }

    @Transactional
    public void deleteOwnedMedia(Long mediaId, Long userId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (!Objects.equals(media.getUserId(), userId)) {
            throw new AccessDeniedException("无权删除他人的媒体");
        }
        if (mediaFileMapper.deleteById(mediaId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "媒体记录状态已变化");
        }
        if (media.getObjectId() != null
                && storageObjectMapper.decrementReference(media.getObjectId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "存储对象引用状态异常");
        }
    }
}
