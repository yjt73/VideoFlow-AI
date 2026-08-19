package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskCompletionServiceTest {

    private AnalysisTaskStateService stateService;
    private MediaFileMapper mediaFileMapper;
    private StringRedisTemplate redisTemplate;
    private AnalysisTaskCompletionService service;

    @BeforeEach
    void setUp() {
        stateService = mock(AnalysisTaskStateService.class);
        mediaFileMapper = mock(MediaFileMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        service = new AnalysisTaskCompletionService(
                stateService,
                mediaFileMapper,
                redisTemplate
        );
    }

    @Test
    void doesNotWriteResultsWhenCancellationWonTheStateRace() {
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(false);

        service.complete(
                "task-1",
                9L,
                new AiAnalysisResult("transcript", "summary")
        );

        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
    }

    @Test
    void writesResultsOnlyAfterTheRunningTaskIsClaimedAsSuccessful() {
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setUserId(3L);
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);
        when(mediaFileMapper.selectById(9L)).thenReturn(media);

        service.complete(
                "task-1",
                9L,
                new AiAnalysisResult("transcript", "summary")
        );

        verify(mediaFileMapper).updateById(media);
        verify(redisTemplate).delete("media:list:user:3");
    }
}
