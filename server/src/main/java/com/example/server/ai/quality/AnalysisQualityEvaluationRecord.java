package com.example.server.ai.quality;

import java.math.BigDecimal;

public record AnalysisQualityEvaluationRecord(
        String taskId,
        Long mediaId,
        String evaluator,
        BigDecimal transcriptAccuracy,
        BigDecimal timestampAccuracy,
        BigDecimal conclusionCoverage,
        BigDecimal hallucinationRate,
        String notes
) {
}
