package com.example.server.service;

import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoContextServiceTest {

    private final VideoContextService service = new VideoContextService();

    @Test
    void keepsOcrWhenVideoHasNoTranscript() {
        List<VideoContext.VideoSegment> segments = service.merge(List.of(), List.of(
                new VideoContextService.FramePart(0, "标题", "frame_1.jpg"),
                new VideoContextService.FramePart(65_000, "结尾", "frame_2.jpg")));

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).ocrTexts()).containsExactly("标题");
        assertThat(segments.get(1).startMs()).isEqualTo(60_000);
        assertThat(segments.get(1).ocrTexts()).containsExactly("结尾");
    }

    @Test
    void combinesAsrAndOcrByTimeWithoutDroppingLaterFrames() {
        List<VideoContext.VideoSegment> segments = service.merge(
                List.of(new VideoContextService.TranscriptPart(0, 60_000, "语音")),
                List.of(new VideoContextService.FramePart(1_000, "画面", "first.jpg"),
                        new VideoContextService.FramePart(62_000, "尾页", "last.jpg")));

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).transcript()).isEqualTo("语音");
        assertThat(segments.get(0).ocrTexts()).containsExactly("画面");
        assertThat(segments.get(1).ocrTexts()).containsExactly("尾页");
    }
}
