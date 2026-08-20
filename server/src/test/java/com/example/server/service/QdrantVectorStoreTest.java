package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantVectorStoreTest {

    @Test
    void derivesStablePointIdsFromMediaAndTimeRange() {
        assertThat(QdrantVectorStore.pointId(7L, 0L, 1_000L))
                .isEqualTo(QdrantVectorStore.pointId(7L, 0L, 1_000L));
        assertThat(QdrantVectorStore.pointId(7L, 0L, 1_000L))
                .isNotEqualTo(QdrantVectorStore.pointId(8L, 0L, 1_000L));
    }
}
