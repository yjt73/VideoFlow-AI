package com.example.server.storage.service;

import com.example.server.api.DependencyUnavailableException;
import com.example.server.storage.config.S3StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URI;
import java.time.Duration;

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
}
