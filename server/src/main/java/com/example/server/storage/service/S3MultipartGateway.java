package com.example.server.storage.service;

import java.net.URI;
import java.time.Duration;

public interface S3MultipartGateway {

    String create(String objectKey, String contentType);

    URI presignPart(
            String objectKey,
            String providerUploadId,
            int partNumber,
            Duration ttl
    );
}
