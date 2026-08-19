package com.example.server.service;

import com.example.server.entity.MediaFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoQaToolServiceTest {

    private final VideoQaToolService toolService = new VideoQaToolService(null);

    @Test
    void querySubtitleByKeywordReturnsTimestampedCitation() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setTranscriptText("""
                [00:01:20] 这里介绍课程评分规则和作业提交方式。
                [00:03:10] 这里开始讲考试安排。
                """);
        mediaFile.setAiSummary("总结：课程包含评分规则。");

        VideoQaToolResult result = toolService.querySubtitleByKeyword(mediaFile, "评分规则是什么？");

        assertThat(result.toolName()).isEqualTo("query_subtitle_by_keyword");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).timestampMs()).isEqualTo(80000L);
        assertThat(result.citations().get(0).content()).contains("评分规则");
    }

    @Test
    void querySubtitleByKeywordFallsBackToSummaryWhenTranscriptMisses() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setTranscriptText("没有相关内容");
        mediaFile.setAiSummary("评分规则：作业占百分之四十。");

        VideoQaToolResult result = toolService.querySubtitleByKeyword(mediaFile, "评分规则");

        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).source()).isEqualTo("SUMMARY");
    }
}
