package com.example.server.storage.service;

import com.example.server.storage.api.CompletedPartRequest;
import com.example.server.storage.config.S3StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwsS3MultipartGatewayTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final AwsS3MultipartGateway gateway = new AwsS3MultipartGateway(
            s3Client,
            presigner,
            new S3StorageProperties(
                    "http://localhost:9000",
                    "minioadmin",
                    "minioadmin",
                    "media",
                    "us-east-1",
                    List.of("http://localhost:5173"),
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(15)
            )
    );

    @Test
    void listsAllUploadedPartsAcrossPages() {
        when(s3Client.listParts(argThat((ListPartsRequest request) ->
                request != null && request.partNumberMarker() == null
        ))).thenReturn(ListPartsResponse.builder()
                .isTruncated(true)
                .nextPartNumberMarker(1)
                .parts(Part.builder().partNumber(1).eTag("\"etag-1\"").build())
                .build());
        when(s3Client.listParts(argThat((ListPartsRequest request) ->
                request != null && Integer.valueOf(1).equals(request.partNumberMarker())
        ))).thenReturn(ListPartsResponse.builder()
                .isTruncated(false)
                .parts(Part.builder().partNumber(2).eTag("etag-2").build())
                .build());

        assertThat(gateway.listParts("temporary/video.mp4", "provider-1"))
                .containsExactly(
                        new CompletedPartRequest(1, "etag-1"),
                        new CompletedPartRequest(2, "etag-2")
                );
    }
}
