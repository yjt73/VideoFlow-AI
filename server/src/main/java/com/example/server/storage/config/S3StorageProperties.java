package com.example.server.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "storage.s3")
public record S3StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        List<String> allowedOrigins,
        Duration downloadUrlTtl,
        Duration uploadUrlTtl
) {
}
