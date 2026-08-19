package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkerTest {

    @Test
    void groupsTimestampedSegmentsIntoFiveMinuteEvidenceChunks() {
        RagChunker chunker = new RagChunker();

        List<RagChunk> chunks = chunker.chunk(List.of(
                new RagTextSegment(0, 0L, 60_000L, "TRANSCRIPT", "开场介绍"),
                new RagTextSegment(1, 240_000L, 300_000L, "TRANSCRIPT", "上传流程"),
                new RagTextSegment(2, 300_000L, 360_000L, "TRANSCRIPT", "任务分析"),
                new RagTextSegment(3, 720_000L, 780_000L, "TRANSCRIPT", "问答检索")
        ));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).startMs()).isZero();
        assertThat(chunks.get(0).endMs()).isEqualTo(300_000L);
        assertThat(chunks.get(0).content()).contains("开场介绍", "上传流程");
        assertThat(chunks.get(1).startMs()).isEqualTo(300_000L);
        assertThat(chunks.get(1).content()).isEqualTo("任务分析");
        assertThat(chunks.get(2).startMs()).isEqualTo(600_000L);
        assertThat(chunks.get(2).endMs()).isEqualTo(780_000L);
    }

    @Test
    void keepsUntimestampedContentAsOneFallbackChunk() {
        RagChunker chunker = new RagChunker();

        List<RagChunk> chunks = chunker.chunk(List.of(
                new RagTextSegment(0, null, null, "SUMMARY", "第一段"),
                new RagTextSegment(1, null, null, "SUMMARY", "第二段")
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).startMs()).isNull();
        assertThat(chunks.get(0).endMs()).isNull();
        assertThat(chunks.get(0).content()).isEqualTo("第一段\n第二段");
    }
}
