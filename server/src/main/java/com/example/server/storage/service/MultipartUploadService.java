package com.example.server.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.dto.UploadState;
import com.example.server.entity.MediaFile;
import com.example.server.storage.api.MultipartCheckRequest;
import com.example.server.storage.api.MultipartCheckView;
import com.example.server.storage.api.PresignedPartView;
import com.example.server.storage.domain.MultipartUploadSession;
import com.example.server.storage.domain.MultipartUploadStatus;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.MultipartUploadSessionMapper;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class MultipartUploadService {

    public static final long PART_SIZE = 5L * 1024 * 1024;
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StorageObjectMapper storageObjectMapper;
    private final MultipartUploadSessionMapper sessionMapper;
    private final MediaReferenceService mediaReferenceService;
    private final StorageObjectKeyFactory keyFactory;
    private final S3MultipartGateway gateway;
    private final Duration uploadUrlTtl;
    private final String bucketName;

    public MultipartUploadService(
            StorageObjectMapper storageObjectMapper,
            MultipartUploadSessionMapper sessionMapper,
            MediaReferenceService mediaReferenceService,
            StorageObjectKeyFactory keyFactory,
            S3MultipartGateway gateway,
            @Value("${storage.s3.upload-url-ttl:15m}") Duration uploadUrlTtl,
            @Value("${storage.s3.bucket:media}") String bucketName
    ) {
        this.storageObjectMapper = storageObjectMapper;
        this.sessionMapper = sessionMapper;
        this.mediaReferenceService = mediaReferenceService;
        this.keyFactory = keyFactory;
        this.gateway = gateway;
        this.uploadUrlTtl = uploadUrlTtl;
        this.bucketName = bucketName;
    }

    @Transactional
    public MultipartCheckView check(MultipartCheckRequest request, Long userId) {
        validate(request);
        Optional<MediaFile> existingMedia =
                mediaReferenceService.findOwnedByHash(userId, request.contentHash());
        if (existingMedia.isPresent()) {
            return completed(existingMedia.get(), request.totalParts());
        }

        StorageObject existingObject = storageObjectMapper.selectOne(
                new LambdaQueryWrapper<StorageObject>()
                        .eq(StorageObject::getContentHash, request.contentHash())
                        .eq(StorageObject::getStatus, StorageObjectStatus.ACTIVE)
                        .last("LIMIT 1")
        );
        if (existingObject != null) {
            MediaFile media = mediaReferenceService.createReference(
                    existingObject,
                    userId,
                    request.filename()
            );
            return completed(media, request.totalParts());
        }

        LocalDateTime now = LocalDateTime.now();
        MultipartUploadSession resumable = sessionMapper.selectOne(
                new LambdaQueryWrapper<MultipartUploadSession>()
                        .eq(MultipartUploadSession::getUserId, userId)
                        .eq(MultipartUploadSession::getContentHash, request.contentHash())
                        .eq(MultipartUploadSession::getStatus, MultipartUploadStatus.INITIATED)
                        .gt(MultipartUploadSession::getExpiresAt, now)
                        .last("LIMIT 1")
        );
        if (resumable != null) {
            return uploading(resumable);
        }

        String sessionId = UUID.randomUUID().toString();
        String objectKey = keyFactory.temporaryKey(userId, sessionId, request.filename());
        String providerUploadId = gateway.create(objectKey, request.contentType());
        MultipartUploadSession session = new MultipartUploadSession();
        session.setSessionId(sessionId);
        session.setProviderUploadId(providerUploadId);
        session.setUserId(userId);
        session.setFilename(request.filename());
        session.setContentHash(request.contentHash());
        session.setContentType(request.contentType());
        session.setSizeBytes(request.sizeBytes());
        session.setBucketName(bucketName);
        session.setTemporaryObjectKey(objectKey);
        session.setTotalParts(request.totalParts());
        session.setPartSize(PART_SIZE);
        session.setStatus(MultipartUploadStatus.INITIATED);
        session.setExpiresAt(now.plus(SESSION_TTL));
        sessionMapper.insert(session);
        return uploading(session);
    }

    public PresignedPartView presignPart(String sessionId, int partNumber, Long userId) {
        MultipartUploadSession session = findSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new AccessDeniedException("无权操作该上传会话");
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getStatus() != MultipartUploadStatus.INITIATED
                || !session.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED, "上传会话已失效");
        }
        if (partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分片序号超出范围");
        }
        return new PresignedPartView(
                partNumber,
                gateway.presignPart(
                        session.getTemporaryObjectKey(),
                        session.getProviderUploadId(),
                        partNumber,
                        uploadUrlTtl
                ).toString(),
                now.plus(uploadUrlTtl)
        );
    }

    private MultipartUploadSession findSession(String sessionId) {
        MultipartUploadSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<MultipartUploadSession>()
                        .eq(MultipartUploadSession::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        return session;
    }

    private void validate(MultipartCheckRequest request) {
        if (request == null
                || request.filename() == null
                || request.filename().isBlank()
                || request.filename().length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不合法");
        }
        if (request.contentHash() == null || !request.contentHash().matches("[0-9a-f]{32}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MD5 必须为 32 位小写十六进制");
        }
        if (request.sizeBytes() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小必须大于 0");
        }
        if (request.contentType() == null
                || request.contentType().isBlank()
                || request.contentType().length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件类型不合法");
        }
        long expectedParts = (request.sizeBytes() + PART_SIZE - 1) / PART_SIZE;
        if (request.totalParts() != expectedParts) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分片数量与文件大小不匹配");
        }
    }

    private MultipartCheckView completed(MediaFile media, int totalParts) {
        return new MultipartCheckView(
                UploadState.COMPLETED,
                null,
                media,
                PART_SIZE,
                totalParts,
                null
        );
    }

    private MultipartCheckView uploading(MultipartUploadSession session) {
        return new MultipartCheckView(
                UploadState.UPLOADING,
                session.getSessionId(),
                null,
                session.getPartSize(),
                session.getTotalParts(),
                session.getExpiresAt()
        );
    }
}
