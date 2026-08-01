package com.example.server.storage.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BucketSecurityInitializerTest {

    @Test
    void initializesPrivateBucketCorsAndLifecycleRules() {
        S3Client s3Client = mock(S3Client.class);
        S3StorageProperties properties = properties();
        BucketSecurityInitializer initializer = new BucketSecurityInitializer(s3Client, properties);

        initializer.initialize();

        verify(s3Client).deleteBucketPolicy(any(DeleteBucketPolicyRequest.class));
        verify(s3Client).putBucketCors(argThat((PutBucketCorsRequest request) -> {
            var rule = request.corsConfiguration().corsRules().getFirst();
            return rule.allowedMethods().containsAll(List.of("GET", "PUT", "HEAD"))
                    && rule.exposeHeaders().contains("ETag");
        }));
        verify(s3Client).putBucketLifecycleConfiguration(argThat((PutBucketLifecycleConfigurationRequest request) ->
                request.lifecycleConfiguration().rules().size() == 2));
    }

    @Test
    void createsBucketWhenItDoesNotExist() {
        S3Client s3Client = mock(S3Client.class);
        doThrow(NoSuchBucketException.builder().message("missing").build())
                .when(s3Client).headBucket(any(HeadBucketRequest.class));
        BucketSecurityInitializer initializer = new BucketSecurityInitializer(s3Client, properties());

        initializer.initialize();

        verify(s3Client).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void ignoresMissingBucketPolicy() {
        S3Client s3Client = mock(S3Client.class);
        doThrow(S3Exception.builder().statusCode(404).message("missing policy").build())
                .when(s3Client).deleteBucketPolicy(any(DeleteBucketPolicyRequest.class));
        BucketSecurityInitializer initializer = new BucketSecurityInitializer(s3Client, properties());

        initializer.initialize();

        verify(s3Client).putBucketCors(any(PutBucketCorsRequest.class));
        verify(s3Client).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void ignoresUnsupportedCorsConfiguration() {
        S3Client s3Client = mock(S3Client.class);
        doThrow(S3Exception.builder().statusCode(501).message("unsupported cors").build())
                .when(s3Client).putBucketCors(any(PutBucketCorsRequest.class));
        BucketSecurityInitializer initializer = new BucketSecurityInitializer(s3Client, properties());

        initializer.initialize();

        verify(s3Client).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void ignoresUnsupportedLifecycleConfiguration() {
        S3Client s3Client = mock(S3Client.class);
        doThrow(S3Exception.builder().statusCode(400).message("invalid lifecycle").build())
                .when(s3Client).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
        BucketSecurityInitializer initializer = new BucketSecurityInitializer(s3Client, properties());

        initializer.initialize();

        verify(s3Client).putBucketCors(any(PutBucketCorsRequest.class));
    }

    private S3StorageProperties properties() {
        return new S3StorageProperties(
                "http://localhost:9000",
                "minioadmin",
                "minioadmin",
                "media",
                "us-east-1",
                List.of("http://localhost:5173"),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15)
        );
    }
}
