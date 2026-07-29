package com.example.server.storage.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StorageObjectKeyFactoryTest {

    private final StorageObjectKeyFactory factory = new StorageObjectKeyFactory();

    @Test
    void buildsContentAddressedCanonicalKey() {
        assertThat(factory.canonicalKey("0123456789abcdef0123456789abcdef"))
                .isEqualTo("media/01/0123456789abcdef0123456789abcdef");
    }

    @Test
    void sanitizesFilenameInTemporaryKey() {
        String key = factory.temporaryKey(3L, "session-1", "../../课程.mp4");

        assertThat(key)
                .startsWith("temporary/3/session-1/")
                .doesNotContain("..", "\\");
    }

    @Test
    void rejectsInvalidContentHash() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.canonicalKey("not-an-md5"));
    }
}
