package com.example.server.storage.service;

import com.example.server.api.DependencyUnavailableException;
import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.config.S3StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class AwsS3MultipartGateway implements S3MultipartGateway {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final S3StorageProperties properties;

    public AwsS3MultipartGateway(
            S3Client s3Client,
            S3Presigner presigner,
            S3StorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String create(String objectKey, String contentType) {
        try {
            return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .build())
                    .uploadId();
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法创建上传会话", exception);
        }
    }

    @Override
    public URI presignPart(
            String objectKey,
            String providerUploadId,
            int partNumber,
            Duration ttl
    ) {
        try {
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .uploadId(providerUploadId)
                    .partNumber(partNumber)
                    .build();
            return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                            .signatureDuration(ttl)
                            .uploadPartRequest(request)
                            .build())
                    .url()
                    .toURI();
        } catch (Exception exception) {
            throw new DependencyUnavailableException("对象存储暂时无法生成分片上传地址", exception);
        }
    }

    @Override
    public void complete(
            String objectKey,
            String uploadId,
            List<CompletedPartRequest> parts
    ) {
        try {
            List<CompletedPart> completedParts = parts.stream()
                    .map(part -> CompletedPart.builder()
                            .partNumber(part.partNumber())
                            .eTag(part.etag())
                            .build())
                    .toList();
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build())
                    .build());
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法完成分片合并", exception);
        }
    }

    @Override
    public void abort(String objectKey, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法取消上传", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法读取文件", exception);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .copySource(encodedCopySource(sourceKey))
                    .destinationBucket(properties.bucket())
                    .destinationKey(targetKey)
                    .build());
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法固化文件", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("对象存储暂时无法删除文件", exception);
        }
    }

    private String encodedCopySource(String objectKey) {
        return URLEncoder.encode(
                        properties.bucket() + "/" + objectKey,
                        StandardCharsets.UTF_8
                )
                .replace("+", "%20")
                .replace("%2F", "/");
    }
}
