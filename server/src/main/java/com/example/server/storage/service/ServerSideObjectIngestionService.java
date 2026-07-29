package com.example.server.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.storage.domain.StorageObject;
import com.example.server.storage.domain.StorageObjectStatus;
import com.example.server.storage.persistence.StorageObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ServerSideObjectIngestionService {

    private final StorageObjectMapper storageObjectMapper;
    private final MediaReferenceService mediaReferenceService;
    private final StorageObjectKeyFactory keyFactory;
    private final S3MultipartGateway gateway;
    private final String bucketName;

    public ServerSideObjectIngestionService(
            StorageObjectMapper storageObjectMapper,
            MediaReferenceService mediaReferenceService,
            StorageObjectKeyFactory keyFactory,
            S3MultipartGateway gateway,
            @Value("${storage.s3.bucket:media}") String bucketName
    ) {
        this.storageObjectMapper = storageObjectMapper;
        this.mediaReferenceService = mediaReferenceService;
        this.keyFactory = keyFactory;
        this.gateway = gateway;
        this.bucketName = bucketName;
    }

    public MediaFile ingest(
            File file,
            String filename,
            String contentType,
            Long userId
    ) {
        validate(file, filename, contentType, userId);
        String contentHash = calculateMd5(file);
        StorageObject existing = findActive(contentHash);
        if (existing != null) {
            return mediaReferenceService.createReference(existing, userId, filename);
        }

        String objectKey = keyFactory.canonicalKey(contentHash);
        gateway.put(objectKey, file.toPath(), contentType);
        StorageObject created = new StorageObject();
        created.setContentHash(contentHash);
        created.setBucketName(bucketName);
        created.setObjectKey(objectKey);
        created.setSizeBytes(file.length());
        created.setContentType(contentType);
        created.setReferenceCount(0);
        created.setStatus(StorageObjectStatus.ACTIVE);
        created.setDeleteAttempts(0);
        try {
            storageObjectMapper.insert(created);
        } catch (DuplicateKeyException exception) {
            StorageObject concurrent = findActive(contentHash);
            if (concurrent == null) {
                throw exception;
            }
            created = concurrent;
        }
        return mediaReferenceService.createReference(created, userId, filename);
    }

    private StorageObject findActive(String contentHash) {
        return storageObjectMapper.selectOne(
                new LambdaQueryWrapper<StorageObject>()
                        .eq(StorageObject::getContentHash, contentHash)
                        .eq(StorageObject::getStatus, StorageObjectStatus.ACTIVE)
                        .last("LIMIT 1")
        );
    }

    private String calculateMd5(File file) {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取待导入文件");
        }
    }

    private void validate(File file, String filename, String contentType, Long userId) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "待导入文件不存在或为空");
        }
        if (filename == null || filename.isBlank() || filename.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不合法");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件类型不合法");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户身份无效");
        }
    }
}
