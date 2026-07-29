package com.example.server.storage.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectIntegrityVerifierTest {

    private final ObjectIntegrityVerifier verifier = new ObjectIntegrityVerifier();

    @Test
    void computesMd5AndSizeInOneStreamingPass() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        IntegrityResult result = verifier.verify(new ByteArrayInputStream(content));

        assertThat(result.md5()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        assertThat(result.sizeBytes()).isEqualTo(5);
    }
}
