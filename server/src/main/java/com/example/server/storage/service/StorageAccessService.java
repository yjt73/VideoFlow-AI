package com.example.server.storage.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.api.MediaAccessView;
import com.example.server.storage.config.S3StorageProperties;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class StorageAccessService {

    private final MediaFileMapper mediaFileMapper;
    private final StorageObjectMapper storageObjectMapper;
    private final S3MultipartGateway gateway;
    private final S3StorageProperties properties;

    public StorageAccessService(
            MediaFileMapper mediaFileMapper,
            StorageObjectMapper storageObjectMapper,
            S3MultipartGateway gateway,
            S3StorageProperties properties
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.storageObjectMapper = storageObjectMapper;
        this.gateway = gateway;
        this.properties = properties;
    }

    public MediaAccessView accessUrl(Long mediaId, Long userId) {
        MediaFile media = requireMedia(mediaId);
        if (!Objects.equals(media.getUserId(), userId)) {
            throw new AccessDeniedException("无权访问他人的媒体");
        }
        URI url = presign(media);
        return new MediaAccessView(
                url.toString(),
                LocalDateTime.now().plus(properties.downloadUrlTtl())
        );
    }

    public URI internalGetUrl(Long mediaId) {
        return presign(requireMedia(mediaId));
    }

    private MediaFile requireMedia(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (media.getObjectId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体尚未关联存储对象");
        }
        return media;
    }

    private URI presign(MediaFile media) {
        StorageObject object = storageObjectMapper.selectById(media.getObjectId());
        if (object == null || object.getStatus() != StorageObjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体存储对象不存在");
        }
        return gateway.presignGet(object.getObjectKey(), properties.downloadUrlTtl());
    }
}
