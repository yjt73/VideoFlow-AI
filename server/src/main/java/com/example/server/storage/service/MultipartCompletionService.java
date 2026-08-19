package com.example.server.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Service
public class MultipartCompletionService {

    private final MultipartUploadSessionMapper sessionMapper;
    private final StorageObjectMapper storageObjectMapper;
    private final MediaFileMapper mediaFileMapper;
    private final MediaReferenceService mediaReferenceService;
    private final StorageObjectKeyFactory keyFactory;
    private final S3MultipartGateway gateway;
    private final ObjectIntegrityVerifier integrityVerifier;
    private final MultipartUploadSessionCache cache;

    public MultipartCompletionService(
            MultipartUploadSessionMapper sessionMapper,
            StorageObjectMapper storageObjectMapper,
            MediaFileMapper mediaFileMapper,
            MediaReferenceService mediaReferenceService,
            StorageObjectKeyFactory keyFactory,
            S3MultipartGateway gateway,
            ObjectIntegrityVerifier integrityVerifier,
            MultipartUploadSessionCache cache
    ) {
        this.sessionMapper = sessionMapper;
        this.storageObjectMapper = storageObjectMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.mediaReferenceService = mediaReferenceService;
        this.keyFactory = keyFactory;
        this.gateway = gateway;
        this.integrityVerifier = integrityVerifier;
        this.cache = cache;
    }

    public MediaFile complete(
            String sessionId,
            Long userId
    ) {
        MultipartUploadSession session = requireOwnedSession(sessionId, userId);
        if (session.getStatus() == MultipartUploadStatus.COMPLETED) {
            MediaFile media = mediaFileMapper.selectById(session.getMediaId());
            if (media == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "已完成上传的媒体记录不存在");
            }
            cache.evict(sessionId);
            return media;
        }
        if (session.getStatus() != MultipartUploadStatus.INITIATED
                || sessionMapper.claimCompleting(sessionId, userId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传会话当前无法完成");
        }
        try {
            List<CompletedPartRequest> actualParts = gateway.listParts(
                    session.getTemporaryObjectKey(),
                    session.getProviderUploadId()
            );
            validateParts(actualParts);
            if (actualParts.size() != session.getTotalParts()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储中的分片数量不完整");
            }
            gateway.complete(
                    session.getTemporaryObjectKey(),
                    session.getProviderUploadId(),
                    actualParts
            );
        } catch (RuntimeException exception) {
            sessionMapper.restoreInitiated(sessionId);
            throw exception;
        }

        IntegrityResult integrity;
        //打开合并后的文件并得到文件流
        try (InputStream stream = gateway.open(session.getTemporaryObjectKey())) {
            //校验文件完整性
            integrity = integrityVerifier.verify(stream);
        } catch (BusinessException exception) {
            cache.evict(sessionId);
            sessionMapper.markFailed(sessionId, "对象完整性校验读取失败");
            throw exception;
        } catch (Exception exception) {
            cache.evict(sessionId);
            sessionMapper.markFailed(sessionId, "对象完整性校验读取失败");
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "对象完整性校验读取失败");
        }
        if (!session.getContentHash().equals(integrity.md5())
                || session.getSizeBytes() != integrity.sizeBytes()) {
            //删除redis缓存进度 + 设置session状态为failed
            cache.evict(sessionId);
            sessionMapper.markFailed(sessionId, "对象完整性校验失败");
            gateway.delete(session.getTemporaryObjectKey());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象完整性校验失败");
        }

        StorageObject storageObject = resolveStorageObject(session);
        MediaFile media = mediaReferenceService.createReference(
                storageObject,
                userId,
                session.getFilename()
        );
        //将session状态设置为完成
        if (sessionMapper.markCompleted(sessionId, media.getId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传完成状态更新失败");
        }
        gateway.delete(session.getTemporaryObjectKey());
        cache.evict(sessionId);
        return media;
    }

    public void abort(String sessionId, Long userId) {
        MultipartUploadSession session = requireOwnedSession(sessionId, userId);
        if (session.getStatus() == MultipartUploadStatus.ABORTED) {
            cache.evict(sessionId);
            return;
        }
        if (session.getStatus() == MultipartUploadStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "已完成上传不能取消");
        }
        if (session.getStatus() != MultipartUploadStatus.INITIATED) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传会话当前不能取消");
        }
        gateway.abort(session.getTemporaryObjectKey(), session.getProviderUploadId());
        if (sessionMapper.markAborted(sessionId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "上传会话状态已变化");
        }
        cache.evict(sessionId);
    }

    private StorageObject resolveStorageObject(MultipartUploadSession session) {
        StorageObject existing = findActiveObject(session.getContentHash());
        if (existing != null) {
            return existing;
        }
        String canonicalKey = keyFactory.canonicalKey(session.getContentHash());
        gateway.copy(session.getTemporaryObjectKey(), canonicalKey);
        StorageObject created = new StorageObject();
        created.setContentHash(session.getContentHash());
        created.setBucketName(session.getBucketName());
        created.setObjectKey(canonicalKey);
        created.setSizeBytes(session.getSizeBytes());
        created.setContentType(session.getContentType());
        created.setReferenceCount(0);
        created.setStatus(StorageObjectStatus.ACTIVE);
        created.setDeleteAttempts(0);
        try {
            storageObjectMapper.insert(created);
            return created;
        } catch (DuplicateKeyException exception) {
            StorageObject concurrent = findActiveObject(session.getContentHash());
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    private StorageObject findActiveObject(String contentHash) {
        return storageObjectMapper.selectOne(
                new LambdaQueryWrapper<StorageObject>()
                        .eq(StorageObject::getContentHash, contentHash)
                        .eq(StorageObject::getStatus, StorageObjectStatus.ACTIVE)
                        .last("LIMIT 1")
        );
    }

    private MultipartUploadSession requireOwnedSession(String sessionId, Long userId) {
        MultipartUploadSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<MultipartUploadSession>()
                        .eq(MultipartUploadSession::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new AccessDeniedException("无权操作该上传会话");
        }
        return session;
    }

    private void validateParts(List<CompletedPartRequest> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MinIO 中没有可合并的分片");
        }
        for (int index = 0; index < parts.size(); index++) {
            CompletedPartRequest part = parts.get(index);
            if (part == null
                    || part.partNumber() != index + 1
                    || part.etag() == null
                    || part.etag().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分片必须连续有序且包含 ETag");
            }
        }
    }
}
