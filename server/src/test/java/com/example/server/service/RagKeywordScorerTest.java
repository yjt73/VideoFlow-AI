package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagKeywordScorerTest {

    @Test
    void givesHigherScoreToSegmentsMatchingMoreQuestionTerms() {
        RagKeywordScorer scorer = new RagKeywordScorer();

        double high = scorer.score("Redis 不可用 时 上传 会 暂停 并 续传", "Redis 上传 续传");
        double low = scorer.score("MinIO 合并 完成 后 生成 任务", "Redis 上传 续传");

        assertThat(high).isGreaterThan(low);
        assertThat(high).isGreaterThan(0);
    }

    @Test
    void returnsZeroWhenQuestionOrTextIsBlank() {
        RagKeywordScorer scorer = new RagKeywordScorer();

        assertThat(scorer.score("", "Redis 上传")).isZero();
        assertThat(scorer.score("Redis 上传", " ")).isZero();
    }

    @Test
    void matchesChineseTextWithoutSpacesBySubstring() {
        RagKeywordScorer scorer = new RagKeywordScorer();

        double score = scorer.score("Redis短暂不可用时前端会暂停上传并等待续传", "Redis 上传 续传");

        assertThat(score).isGreaterThan(0);
    }
}
