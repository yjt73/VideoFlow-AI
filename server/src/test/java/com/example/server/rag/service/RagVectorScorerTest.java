package com.example.server.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagVectorScorerTest {

    @Test
    void calculatesCosineSimilarity() {
        RagVectorScorer scorer = new RagVectorScorer();

        double sameDirection = scorer.cosine(List.of(1.0, 0.0), List.of(2.0, 0.0));
        double orthogonal = scorer.cosine(List.of(1.0, 0.0), List.of(0.0, 1.0));

        assertThat(sameDirection).isEqualTo(1.0);
        assertThat(orthogonal).isEqualTo(0.0);
    }

    @Test
    void returnsZeroForEmptyOrMismatchedVectors() {
        RagVectorScorer scorer = new RagVectorScorer();

        assertThat(scorer.cosine(List.of(), List.of(1.0))).isZero();
        assertThat(scorer.cosine(List.of(1.0), List.of(1.0, 2.0))).isZero();
        assertThat(scorer.cosine(List.of(0.0, 0.0), List.of(1.0, 2.0))).isZero();
    }
}
