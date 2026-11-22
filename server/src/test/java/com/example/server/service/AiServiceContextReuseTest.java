package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServiceContextReuseTest {

    @Test
    void reusesRecognitionForAnotherGoalOnTheSameMedia() {
        AiService service = new AiService();
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        ReflectionTestUtils.setField(service, "agentCheckpointService", checkpoints);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        VideoContext cached = new VideoContext("video.mp4", "原目标", List.of(
                new VideoContext.VideoSegment(0, 60_000, "语音", List.of("画面"), List.of("frame.jpg"))));
        when(checkpoints.loadContext(2L)).thenReturn(cached);

        VideoContext reused = ReflectionTestUtils.invokeMethod(service, "resolveAgentContext",
                media(2L, "video.mp4"), "新目标", "trace", AnalysisMode.GENERAL);

        assertThat(reused.userGoal()).isEqualTo("新目标");
        assertThat(reused.segments()).isEqualTo(cached.segments());
        verify(checkpoints, never()).saveContext(anyLong(), any());
    }

    @Test
    void reusesAsrAndOcrFromSameContentEvenAfterRedisIndexExpires() {
        AiService service = new AiService();
        MediaService mediaService = mock(MediaService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service, "mediaService", mediaService);
        ReflectionTestUtils.setField(service, "agentCheckpointService", checkpoints);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        MediaFile target = media(2L, "target.mp4");
        VideoContext source = new VideoContext("original.mp4", "旧目标", List.of(
                new VideoContext.VideoSegment(0, 60_000, "语音内容", List.of("画面文字"), List.of("frame.jpg"))));
        when(mediaService.sameContentMediaIds(2L, "0123456789abcdef0123456789abcdef"))
                .thenReturn(List.of(1L));
        when(checkpoints.loadContext(1L)).thenReturn(source);

        VideoContext reused = ReflectionTestUtils.invokeMethod(service, "reuseAgentContext",
                target, "新目标", "trace", "0123456789abcdef0123456789abcdef");

        assertThat(reused.source()).isEqualTo("target.mp4");
        assertThat(reused.userGoal()).isEqualTo("新目标");
        assertThat(reused.segments()).isEqualTo(source.segments());
        verify(checkpoints).saveContext(2L, reused);
        verify(values).set(startsWith("analysis:context-owner:"), eq("2"), eq(Duration.ofDays(7)));
    }

    private static MediaFile media(Long id, String path) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setFilePath(path);
        return file;
    }
}
