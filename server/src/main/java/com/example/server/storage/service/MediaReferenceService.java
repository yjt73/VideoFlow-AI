package com.example.server.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class MediaReferenceService {

    private final MediaFileMapper mediaFileMapper;
    private final StorageObjectMapper storageObjectMapper;

    public MediaReferenceService(
            MediaFileMapper mediaFileMapper,
            StorageObjectMapper storageObjectMapper
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.storageObjectMapper = storageObjectMapper;
    }

    @Transactional
    public MediaFile createReference(StorageObject storageObject, Long userId, String filename) {
        Optional<MediaFile> existing = findOwnedByHash(userId, storageObject.getContentHash());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (storageObjectMapper.incrementReference(storageObject.getId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "存储对象当前不可引用");
        }

        MediaFile mediaFile = new MediaFile();
        mediaFile.setUserId(userId);
        mediaFile.setFilename(filename);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setFilePath("");
        mediaFile.setObjectId(storageObject.getId());
        mediaFile.setContentHash(storageObject.getContentHash());
        mediaFileMapper.insert(mediaFile);
        return mediaFile;
    }

    public Optional<MediaFile> findOwnedByHash(Long userId, String contentHash) {
        return Optional.ofNullable(mediaFileMapper.selectOne(
                new LambdaQueryWrapper<MediaFile>()
                        .eq(MediaFile::getUserId, userId)
                        .eq(MediaFile::getContentHash, contentHash)
                        .last("LIMIT 1")
        ));
    }
}
