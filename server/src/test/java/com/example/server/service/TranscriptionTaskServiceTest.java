package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TranscriptionTaskServiceTest {

    @Test
    void usesCachedAsrFromAnalysisWithoutCallingRecognizerAgain() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        VideoTranscriptionService recognizer = mock(VideoTranscriptionService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        MediaService mediaService = mock(MediaService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TaskEventService events = mock(TaskEventService.class);
        MediaFile media = new MediaFile();
        media.setId(7L);
        media.setUserId(1L);
        when(mapper.selectById(7L)).thenReturn(media);
        when(checkpoints.loadContext(7L)).thenReturn(new VideoContext("video.mp4", "目标", List.of(
                new VideoContext.VideoSegment(0, 60_000, "识别出的语音", List.of("OCR"), List.of()))));

        new TranscriptionTaskService(mapper, recognizer, checkpoints, mediaService, redis, events)
                .transcribe(7L);

        assertThat(media.getTranscriptText()).isEqualTo("识别出的语音");
        verifyNoInteractions(recognizer);
        verify(mapper).updateById(media);
    }
}
