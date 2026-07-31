package com.example.server.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagSegmenterTest {

    @Test
    void splitsTimestampedTranscriptIntoSegmentsWithMetadata() {
        RagSegmenter segmenter = new RagSegmenter();

        List<RagTextSegment> segments = segmenter.splitTranscript("""
                [00:00] Redis 短暂不可用时前端会暂停上传
                [00:05] 服务恢复后继续上传已选择的文件
                [00:10] MinIO 分片合并完成后生成任务
                """);

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).segmentIndex()).isZero();
        assertThat(segments.get(0).startMs()).isEqualTo(0L);
        assertThat(segments.get(0).endMs()).isEqualTo(5_000L);
        assertThat(segments.get(0).source()).isEqualTo("TRANSCRIPT");
        assertThat(segments.get(0).content()).contains("Redis");
        assertThat(segments.get(1).startMs()).isEqualTo(5_000L);
        assertThat(segments.get(2).endMs()).isEqualTo(10_000L);
    }

    @Test
    void splitsSummaryIntoParagraphSegmentsWithoutTimestamp() {
        RagSegmenter segmenter = new RagSegmenter();

        List<RagTextSegment> segments = segmenter.splitSummary("""
                第一段总结内容。

                第二段总结内容。
                """);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).source()).isEqualTo("SUMMARY");
        assertThat(segments.get(0).startMs()).isNull();
        assertThat(segments.get(0).content()).isEqualTo("第一段总结内容。");
        assertThat(segments.get(1).segmentIndex()).isEqualTo(1);
    }

    @Test
    void ignoresBlankText() {
        RagSegmenter segmenter = new RagSegmenter();

        assertThat(segmenter.splitTranscript(" \n \t")).isEmpty();
        assertThat(segmenter.splitSummary(null)).isEmpty();
    }
}
