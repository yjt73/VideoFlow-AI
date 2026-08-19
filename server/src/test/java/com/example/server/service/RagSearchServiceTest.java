package com.example.server.service;

import com.example.server.rag.persistence.RagSegment;
import com.example.server.rag.persistence.RagSegmentRepository;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagSearchServiceTest {

    @Test
    void returnsKeywordMatchesWhenEmbeddingServiceFails() {
        RagSegmentRepository repository = mock(RagSegmentRepository.class);
        RagKeywordScorer keywordScorer = new RagKeywordScorer();
        RagVectorScorer vectorScorer = new RagVectorScorer();
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        EmbeddingCacheService cacheService = mock(EmbeddingCacheService.class);
        OperationalAlertService alertService = new OperationalAlertService();
        OperationMetricsService metricsService = new OperationMetricsService(new SimpleMeterRegistry());

        RagSegment segment = new RagSegment();
        segment.setId(1L);
        segment.setMediaId(11L);
        segment.setSegmentIndex(0);
        segment.setSource("VIDEO_CHUNK");
        segment.setContent("Redis 短暂不可用时暂停上传");
        segment.setTokenText("redis 短暂不可用时暂停上传");
        when(repository.selectByUserAndMediaIds(7L, List.of(11L))).thenReturn(List.of(segment));
        when(embeddingUtils.model()).thenReturn("test-model");
        when(cacheService.find(any(), any())).thenReturn(Optional.empty());
        when(embeddingUtils.embed(any())).thenThrow(new IllegalStateException("embedding unavailable"));

        RagSearchService service = new RagSearchService(
                repository,
                keywordScorer,
                vectorScorer,
                embeddingUtils,
                cacheService,
                new ObjectMapper(),
                metricsService,
                alertService
        );

        List<RagSearchResult> results = service.search(7L, List.of(11L), "Redis 上传", 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).vectorScore()).isZero();
        assertThat(results.get(0).keywordScore()).isGreaterThan(0);
    }
}
