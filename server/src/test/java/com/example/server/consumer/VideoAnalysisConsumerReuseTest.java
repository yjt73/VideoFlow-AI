package com.example.server.consumer;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.AnalysisMode;
import com.example.server.service.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoAnalysisConsumerReuseTest {

    @Test
    void reusesDatabaseResultWhenCompletedRedisIndexHasExpired() {
        AiService aiService = mock(AiService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);

        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentState result = mock(AgentState.class);
        AnalysisResult analysis = mock(AnalysisResult.class);
        when(result.result()).thenReturn(analysis);
        when(analysis.toMarkdown()).thenReturn("已复用结果");
        when(checkpoints.loadResult(1L, "目标", AnalysisMode.GENERAL)).thenReturn(result);
        when(aiService.reuseResult(2L, 1L, result, AnalysisMode.GENERAL)).thenReturn(true);

        MediaService mediaService = mock(MediaService.class);
        when(mediaService.exists(2L)).thenReturn(true);
        when(mediaService.sameContentMediaIds(2L, "0123456789abcdef0123456789abcdef"))
                .thenReturn(List.of(1L));

        VideoAnalysisConsumer consumer = new VideoAnalysisConsumer(
                aiService, redisson, redis, checkpoints, mock(RocketMQTemplate.class),
                mock(FailedAnalysisTaskService.class), mediaService, mock(TaskEventService.class), "dead");
        consumer.onMessage(new AnalysisTaskMsg(2L, AnalysisTaskMsg.START_ANALYSIS,
                "0123456789abcdef0123456789abcdef", "目标", "GENERAL"));

        verify(aiService).reuseResult(2L, 1L, result, AnalysisMode.GENERAL);
        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any());
    }
}
