package com.example.server.service;

import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class VideoEvidenceRetrievalService {

    private static final int DEFAULT_LIMIT = 5;
    private final QdrantVectorStore vectorStore;
    private final EmbeddingUtils embeddingUtils;
    private final RagKeywordScorer keywordScorer;
    private final RagVectorScorer vectorScorer = new RagVectorScorer();

    public VideoEvidenceRetrievalService(QdrantVectorStore vectorStore,
                                         EmbeddingUtils embeddingUtils,
                                         RagKeywordScorer keywordScorer) {
        this.vectorStore = vectorStore;
        this.embeddingUtils = embeddingUtils;
        this.keywordScorer = keywordScorer;
    }

    public List<VideoEvidenceHit> retrieve(Long mediaId,
                                            String goal,
                                            List<VideoChunk> chunks) {
        return search(mediaId, goal, chunks);
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        if (mediaId == null || chunks == null || chunks.isEmpty()) return;
        try {
            vectorStore.upsert(mediaId, chunks);
        } catch (RuntimeException ignored) {
            // Qdrant is an optimization; local chunk retrieval remains available.
        }
    }

    public List<VideoEvidenceHit> search(Long mediaId,
                                         String query,
                                         List<VideoChunk> chunks) {
        if (mediaId == null || query == null || query.isBlank()
                || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Double> queryEmbedding = embed(query);
        try {
            if (!queryEmbedding.isEmpty()) {
                List<QdrantVectorStore.VectorHit> remote = vectorStore.search(
                        mediaId, queryEmbedding, DEFAULT_LIMIT);
                if (!remote.isEmpty()) {
                    return remote.stream()
                            .map(hit -> scoreRemote(hit, query))
                            .sorted(Comparator.comparingDouble(ScoredHit::score).reversed())
                            .limit(DEFAULT_LIMIT)
                            .map(ScoredHit::hit)
                            .toList();
                }
            }
        } catch (RuntimeException ignored) {
            // Qdrant 不可用时使用当前任务已构建的本地语义块。
        }
        return localSearch(query, queryEmbedding, chunks);
    }

    private List<VideoEvidenceHit> localSearch(String query,
                                                List<Double> queryEmbedding,
                                                List<VideoChunk> chunks) {
        return chunks.stream()
                .map(chunk -> scoreLocal(chunk, query, queryEmbedding))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed())
                .limit(DEFAULT_LIMIT)
                .map(ScoredHit::hit)
                .toList();
    }

    private ScoredHit scoreRemote(QdrantVectorStore.VectorHit hit, String query) {
        double transcriptScore = keywordScorer.score(hit.transcript(), query);
        double ocrScore = keywordScorer.score(String.join(" ", hit.ocrTexts()), query);
        double score = hit.semanticScore() * 0.60
                + Math.min(1.0, transcriptScore) * 0.25
                + Math.min(1.0, ocrScore) * 0.15;
        return new ScoredHit(new VideoEvidenceHit(
                hit.startMs(), hit.endMs(), hit.source(), hit.snippet(),
                hit.transcript(), hit.ocrTexts()), score);
    }

    private ScoredHit scoreLocal(VideoChunk chunk,
                                 String query,
                                 List<Double> queryEmbedding) {
        String transcript = chunk.rawSegments().stream()
                .map(VideoContext.VideoSegment::transcript)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
        List<String> ocrTexts = chunk.rawSegments().stream()
                .flatMap(segment -> segment.ocrTexts().stream())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        double semanticScore = vectorScorer.cosine(queryEmbedding, chunk.embedding());
        double transcriptScore = keywordScorer.score(transcript + " " + chunk.segmentSummary(), query);
        double ocrScore = keywordScorer.score(String.join(" ", ocrTexts), query);
        double score = semanticScore * 0.60
                + Math.min(1.0, transcriptScore) * 0.25
                + Math.min(1.0, ocrScore) * 0.15;
        return new ScoredHit(new VideoEvidenceHit(
                chunk.startTime(), chunk.endTime(), "VIDEO_CHUNK",
                chunk.segmentSummary(), transcript, ocrTexts), score);
    }

    private List<Double> embed(String query) {
        try {
            List<Double> embedding = embeddingUtils.embed(query);
            return embedding == null ? List.of() : embedding;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private record ScoredHit(VideoEvidenceHit hit, double score) {
    }
}
