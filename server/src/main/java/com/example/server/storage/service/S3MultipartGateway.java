package com.example.server.storage.service;

import com.example.server.storage.api.CompletedPartRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public interface S3MultipartGateway {

    String create(String objectKey, String contentType);

    URI presignPart(
            String objectKey,
            String providerUploadId,
            int partNumber,
            Duration ttl
    );

    void complete(String objectKey, String uploadId, List<CompletedPartRequest> parts);

    void abort(String objectKey, String uploadId);

    InputStream open(String objectKey);

    void copy(String sourceKey, String targetKey);

    void delete(String objectKey);
}
