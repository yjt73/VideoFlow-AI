package com.example.server.ai.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredModelOutputParserTest {

    private final StructuredModelOutputParser parser = new StructuredModelOutputParser();

    @Test
    void parsesJsonInsideMarkdownFence() {
        Sample result = parser.parse("""
                ```json
                {"name":"demo","values":["a","b"]}
                ```
                """, Sample.class);

        assertThat(result.name()).isEqualTo("demo");
        assertThat(result.values()).containsExactly("a", "b");
    }

    @Test
    void parsesJsonWrappedByProse() {
        Sample result = parser.parse("""
                下面是结果：
                {"name":"demo","values":["a"]}
                已完成。
                """, Sample.class);

        assertThat(result.name()).isEqualTo("demo");
        assertThat(result.values()).containsExactly("a");
    }

    @Test
    void rejectsInvalidJsonAfterExtraction() {
        assertThatThrownBy(() -> parser.parse("not json", Sample.class))
                .isInstanceOf(StructuredModelOutputException.class);
    }

    record Sample(String name, List<String> values) {
    }
}
