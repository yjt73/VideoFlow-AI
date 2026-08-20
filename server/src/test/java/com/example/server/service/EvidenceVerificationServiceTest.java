package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceVerificationServiceTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();

    @Test
    void acceptsTranscriptAndOcrEvidenceInsideTheMatchingContextSegment() {
        VideoContext context = context();
        AnalysisResult.Evidence transcript = new AnalysisResult.Evidence(
                500L, "ASR", "upload video", "upload video");
        AnalysisResult.Evidence ocr = new AnalysisResult.Evidence(
                500L, "OCR", "chapter one", "chapter one");

        assertThat(service.supported(context, transcript)).isTrue();
        assertThat(service.supported(context, ocr)).isTrue();
    }

    @Test
    void rejectsEvidenceOutsideTheTimelineUnsupportedSourceOrMissingContent() {
        VideoContext context = context();

        assertThat(service.supported(context,
                new AnalysisResult.Evidence(1_500L, "ASR", "upload video"))).isFalse();
        assertThat(service.supported(context,
                new AnalysisResult.Evidence(500L, "SUMMARY", "upload video"))).isFalse();
        assertThat(service.supported(context,
                new AnalysisResult.Evidence(500L, "ASR", "missing statement"))).isFalse();
    }

    @Test
    void acceptsOnlyConclusionsSupportedByTheEvidence() {
        VideoContext context = context();
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                500L, "ASR", "upload video successfully", "upload video");

        assertThat(service.supportsClaim(context, "The video explains upload video", evidence)).isTrue();
        assertThat(service.supportsClaim(context, "The video explains weather forecasts", evidence)).isFalse();
    }

    private VideoContext context() {
        return new VideoContext(
                "video.mp4",
                "understand upload",
                List.of(new VideoContext.VideoSegment(
                        0L, 1_000L, "upload video successfully",
                        List.of("chapter one"), List.of("frame-1"))));
    }
}
