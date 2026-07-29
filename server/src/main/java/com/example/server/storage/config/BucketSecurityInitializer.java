package com.example.server.storage.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

@Component
public class BucketSecurityInitializer implements ApplicationRunner {

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    public BucketSecurityInitializer(S3Client s3Client, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        ensureBucketExists();
        removePublicPolicy();
        configureCors();
        configureLifecycle();
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(properties.bucket())
                    .build());
        } catch (NoSuchBucketException exception) {
            createBucket();
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            createBucket();
        }
    }

    private void createBucket() {
        s3Client.createBucket(CreateBucketRequest.builder()
                .bucket(properties.bucket())
                .build());
    }

    private void removePublicPolicy() {
        try {
            s3Client.deleteBucketPolicy(DeleteBucketPolicyRequest.builder()
                    .bucket(properties.bucket())
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
        }
    }

    private void configureCors() {
        var rule = software.amazon.awssdk.services.s3.model.CORSRule.builder()
                .allowedOrigins(properties.allowedOrigins())
                .allowedMethods("GET", "PUT", "HEAD")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
        s3Client.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(properties.bucket())
                .corsConfiguration(configuration -> configuration.corsRules(rule))
                .build());
    }

    private void configureLifecycle() {
        LifecycleRule abortIncompleteUploads = LifecycleRule.builder()
                .id("abort-incomplete-multipart-after-24-hours")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("").build())
                .abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder()
                        .daysAfterInitiation(1)
                        .build())
                .build();
        LifecycleRule expireTemporaryObjects = LifecycleRule.builder()
                .id("expire-temporary-objects-after-24-hours")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("temporary/").build())
                .expiration(LifecycleExpiration.builder().days(1).build())
                .build();
        s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(properties.bucket())
                .lifecycleConfiguration(configuration ->
                        configuration.rules(List.of(abortIncompleteUploads, expireTemporaryObjects)))
                .build());
    }
}
