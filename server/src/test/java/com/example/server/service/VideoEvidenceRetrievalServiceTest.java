package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoEvidenceRetrievalServiceTest {

    @Test
    void ordersQdrantEvidenceBySemanticTranscriptAndOcrScore() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        RagKeywordScorer keywordScorer = new RagKeywordScorer();
        VideoEvidenceRetrievalService service =
                new VideoEvidenceRetrievalService(vectorStore, embeddingUtils, keywordScorer);
        when(embeddingUtils.embed("上传流程")).thenReturn(List.of(1.0, 0.0));
        when(vectorStore.search(eq(7L), eq(List.of(1.0, 0.0)), eq(5)))
                .thenReturn(List.of(
                        new QdrantVectorStore.VectorHit(0L, 1_000L, "ASR",
                                "上传成功", "上传流程完成", List.of(), 0.9),
                        new QdrantVectorStore.VectorHit(2_000L, 3_000L, "OCR",
                                "按钮文字", "", List.of("上传"), 0.8)));

        List<com.example.server.dto.VideoEvidenceHit> hits = service.search(
                7L, "上传流程",
                List.of(new VideoChunk(0, 1_000, "上传流程完成", List.of("上传"),
                        List.of(), List.of(1.0, 0.0))));

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).source()).isEqualTo("ASR");
        assertThat(hits.get(0).snippet()).isEqualTo("上传成功");
    }

    @Test
    void fallsBackToLocalChunkScoringWhenQdrantOrEmbeddingFails() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        RagKeywordScorer keywordScorer = new RagKeywordScorer();
        VideoEvidenceRetrievalService service =
                new VideoEvidenceRetrievalService(vectorStore, embeddingUtils, keywordScorer);
        when(embeddingUtils.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        when(vectorStore.search(anyLong(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("qdrant offline"));

        VideoChunk matching = new VideoChunk(0, 1_000, "上传流程完成",
                List.of("上传"), List.of(new VideoContext.VideoSegment(
                0, 1_000, "上传流程完成", List.of(), List.of())), List.of());
        VideoChunk other = new VideoChunk(1_000, 2_000, "天气预报",
                List.of("天气"), List.of(new VideoContext.VideoSegment(
                1_000, 2_000, "天气预报", List.of(), List.of())), List.of());

        List<com.example.server.dto.VideoEvidenceHit> hits =
                service.search(7L, "上传流程", List.of(matching, other));

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).snippet()).contains("上传");
    }
}
