package com.example.server.rag.service;

import com.example.server.ai.artifact.EmbeddingCacheService;
import com.example.server.rag.persistence.RagSegment;
import com.example.server.rag.persistence.RagSegmentRepository;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public RagSearchService(RagSegmentRepository ragSegmentRepository,
                            RagKeywordScorer keywordScorer,
                            RagVectorScorer vectorScorer,
                            EmbeddingUtils embeddingUtils,
                            EmbeddingCacheService embeddingCacheService,
                            ObjectMapper objectMapper) {
        this.ragSegmentRepository = ragSegmentRepository;
        this.keywordScorer = keywordScorer;
        this.vectorScorer = vectorScorer;
        this.embeddingUtils = embeddingUtils;
        this.embeddingCacheService = embeddingCacheService;
        this.objectMapper = objectMapper;
    }

    public List<RagSearchResult> search(Long userId, Collection<Long> mediaIds, String question, int limit) {
        List<RagSegment> segments = ragSegmentRepository.selectByUserAndMediaIds(userId, mediaIds);
        if (segments.isEmpty() || question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        Optional<List<Double>> queryEmbedding = queryEmbedding(question);
        List<ScoredSegment> scoredSegments = segments.stream()
                .map(segment -> score(segment, question, queryEmbedding))
                .filter(segment -> segment.keywordScore() > 0 || segment.vectorScore() > 0)
                .toList();
        Map<Long, Integer> keywordRanks = ranks(scoredSegments, Comparator.comparingDouble(ScoredSegment::keywordScore).reversed());
        Map<Long, Integer> vectorRanks = ranks(scoredSegments, Comparator.comparingDouble(ScoredSegment::vectorScore).reversed());
        return scoredSegments.stream()
                .map(segment -> toResult(segment, keywordRanks, vectorRanks))
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

    private Map<Long, Integer> ranks(List<ScoredSegment> scoredSegments, Comparator<ScoredSegment> comparator) {
        List<ScoredSegment> sorted = new ArrayList<>(scoredSegments);
        sorted.sort(comparator.thenComparing(segment -> segment.segment().getId()));
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).segment().getId(), i + 1);
        }
        return ranks;
    }

    private RagSearchResult toResult(ScoredSegment segment,
                                     Map<Long, Integer> keywordRanks,
                                     Map<Long, Integer> vectorRanks) {
        Long segmentId = segment.segment().getId();
        double fusedScore = rrf(keywordRanks.get(segmentId)) + rrf(vectorRanks.get(segmentId));
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

    private double rrf(Integer rank) {
        return rank == null ? 0 : 1.0 / (60 + rank);
    }

    private record ScoredSegment(RagSegment segment, double keywordScore, double vectorScore) {
    }
}
