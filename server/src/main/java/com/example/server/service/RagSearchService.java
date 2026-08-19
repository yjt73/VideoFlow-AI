package com.example.server.service;

import com.example.server.service.EmbeddingCacheService;
import com.example.server.service.OperationMetricsService;
import com.example.server.service.OperationalAlertService;
import com.example.server.rag.persistence.RagSegment;
import com.example.server.rag.persistence.RagSegmentRepository;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class RagSearchService {

    private static final TypeReference<List<Double>> EMBEDDING_TYPE = new TypeReference<>() {
    };
    private static final double MIN_RELEVANCE_SCORE = 0.05;

    private final RagSegmentRepository ragSegmentRepository;
    private final RagKeywordScorer keywordScorer;
    private final RagVectorScorer vectorScorer;
    private final EmbeddingUtils embeddingUtils;
    private final EmbeddingCacheService embeddingCacheService;
    private final ObjectMapper objectMapper;
    private final OperationMetricsService metricsService;
    private final OperationalAlertService alertService;

    public RagSearchService(RagSegmentRepository ragSegmentRepository,
                            RagKeywordScorer keywordScorer,
                            RagVectorScorer vectorScorer,
                            EmbeddingUtils embeddingUtils,
                            EmbeddingCacheService embeddingCacheService,
                            ObjectMapper objectMapper,
                            OperationMetricsService metricsService,
                            OperationalAlertService alertService) {
        this.ragSegmentRepository = ragSegmentRepository;
        this.keywordScorer = keywordScorer;
        this.vectorScorer = vectorScorer;
        this.embeddingUtils = embeddingUtils;
        this.embeddingCacheService = embeddingCacheService;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.alertService = alertService;
    }

    public List<RagSearchResult> search(Long userId, Collection<Long> mediaIds, String question, int limit) {
        Instant startedAt = Instant.now();
        try {
            List<RagSearchResult> results = doSearch(userId, mediaIds, question, limit);
            metricsService.increment("rag.search", "success");
            metricsService.recordDuration("rag.search", "success", Duration.between(startedAt, Instant.now()));
            if (results.isEmpty()) {
                alertService.warn("rag.search.empty", userId, null, "RAG 检索没有命中片段");
            }
            return results;
        } catch (RuntimeException exception) {
            metricsService.increment("rag.search", "failure");
            metricsService.recordDuration("rag.search", "failure", Duration.between(startedAt, Instant.now()));
            alertService.warn("rag.search.failure", userId, null, exception.getMessage());
            throw exception;
        }
    }

    private List<RagSearchResult> doSearch(Long userId, Collection<Long> mediaIds, String question, int limit) {
        List<RagSegment> segments = ragSegmentRepository.selectByUserAndMediaIds(userId, mediaIds);
        if (segments.isEmpty() || question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        Optional<List<Double>> queryEmbedding = queryEmbedding(question);
        List<ScoredSegment> scoredSegments = segments.stream()
                .map(segment -> score(segment, question, queryEmbedding))
                .filter(segment -> segment.keywordScore() > 0 || segment.vectorScore() > 0)
                .toList();
        return scoredSegments.stream()
                .map(this::toResult)
                .filter(result -> result.fusedScore() >= MIN_RELEVANCE_SCORE)
                .sorted(Comparator.comparingDouble(RagSearchResult::fusedScore).reversed()
                        .thenComparing(Comparator.comparingDouble(RagSearchResult::keywordScore).reversed())
                        .thenComparing(Comparator.comparing(RagSearchResult::segmentId)))
                .limit(limit)
                .toList();
    }

    private Optional<List<Double>> queryEmbedding(String question) {
        if (embeddingUtils == null || embeddingCacheService == null) {
            return Optional.empty();
        }
        try {
            String model = embeddingUtils.model();
            Optional<List<Double>> cached = embeddingCacheService.find(question, model);
            if (cached.isPresent()) {
                return cached;
            }
            List<Double> embedding = embeddingUtils.embed(question);
            embeddingCacheService.save(question, model, embedding);
            return Optional.of(embedding);
        } catch (RuntimeException exception) {
            alertService.warn("rag.embedding.fallback", "Embedding 不可用，已降级为关键词检索");
            return Optional.empty();
        }
    }

    private ScoredSegment score(RagSegment segment, String question, Optional<List<Double>> queryEmbedding) {
        double keywordScore = keywordScorer.score(segment.getTokenText(), question);
        double vectorScore = queryEmbedding
                .flatMap(query -> parseEmbedding(segment.getEmbeddingJson())
                        .map(segmentEmbedding -> vectorScorer.cosine(query, segmentEmbedding)))
                .orElse(0.0);
        return new ScoredSegment(segment, keywordScore, vectorScore);
    }

    private Optional<List<Double>> parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, EMBEDDING_TYPE));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private RagSearchResult toResult(ScoredSegment segment) {
        Long segmentId = segment.segment().getId();
        double fusedScore = Math.min(1.0, segment.keywordScore()) * 0.3
                + segment.vectorScore() * 0.7;
        return new RagSearchResult(
                segmentId,
                segment.segment().getMediaId(),
                segment.segment().getSegmentIndex(),
                segment.segment().getStartMs(),
                segment.segment().getEndMs(),
                segment.segment().getSource(),
                segment.segment().getContent(),
                segment.keywordScore(),
                segment.vectorScore(),
                fusedScore
        );
    }

    private record ScoredSegment(RagSegment segment, double keywordScore, double vectorScore) {
    }
}
