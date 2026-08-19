package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.dto.VideoQaRequest;
import com.example.server.dto.VideoQaResponse;
import com.example.server.qa.persistence.VideoQaHistoryRepository;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoQaServiceTest {

    @Test
    void fallsBackToCitationWhenModelAnswerFails() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(9L);
        mediaFile.setUserId(3L);
        mediaFile.setTranscriptText("[00:00:10] 视频主要讲报销流程和发票提交。");

        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        VideoQaToolService toolService = mock(VideoQaToolService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        VideoQaMemoryService memoryService = mock(VideoQaMemoryService.class);
        VideoQaHistoryRepository historyRepository = mock(VideoQaHistoryRepository.class);

        when(mediaFileMapper.selectById(9L)).thenReturn(mediaFile);
        when(memoryService.loadRecent(3L, 9L, "conversation-1")).thenReturn(List.of());
        VideoQaCitation citation = new VideoQaCitation(10000L, "ASR", "视频主要讲报销流程和发票提交。");
        when(toolService.querySubtitleByKeyword(mediaFile, "视频主要讲了什么"))
                .thenReturn(new VideoQaToolResult("query_subtitle_by_keyword", "找到 1 条相关片段", List.of(citation)));
        when(toolService.loadVideoSegments(mediaFile))
                .thenReturn(new VideoQaToolResult("load_video_segments", "加载视频前 1 个文本片段", List.of(citation)));
        when(toolService.queryTaskStatus(9L, 3L))
                .thenReturn(new VideoQaToolResult("query_task_status", "最近任务状态：SUCCESS", List.of()));
        when(deepSeekUtils.answerQuestion(eq("视频主要讲了什么"), any(), any()))
                .thenThrow(new RuntimeException("request timed out"));

        VideoQaService service = new VideoQaService(
                mediaFileMapper,
                toolService,
                deepSeekUtils,
                new ObjectMapper(),
                memoryService,
                historyRepository
        );

        VideoQaResponse response = service.answer(
                9L,
                3L,
                new VideoQaRequest("conversation-1", "视频主要讲了什么")
        );

        assertThat(response.answer()).isEqualTo("视频主要讲报销流程和发票提交。");
        assertThat(response.citations()).hasSize(2);
        verify(historyRepository).saveMessage(
                eq("conversation-1"),
                eq(3L),
                eq(9L),
                eq("ASSISTANT"),
                eq("视频主要讲报销流程和发票提交。"),
                anyList(),
                anyList()
        );
    }

    @Test
    void returnsAnswerWhenHistoryPersistenceFails() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(9L);
        mediaFile.setUserId(3L);
        mediaFile.setTranscriptText("[00:00:10] 视频主要讲报销流程和发票提交。");

        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        VideoQaToolService toolService = mock(VideoQaToolService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        VideoQaMemoryService memoryService = mock(VideoQaMemoryService.class);
        VideoQaHistoryRepository historyRepository = mock(VideoQaHistoryRepository.class);

        when(mediaFileMapper.selectById(9L)).thenReturn(mediaFile);
        when(memoryService.loadRecent(3L, 9L, "conversation-1")).thenReturn(List.of());
        VideoQaCitation citation = new VideoQaCitation(10000L, "ASR", "视频主要讲报销流程和发票提交。");
        when(toolService.querySubtitleByKeyword(mediaFile, "视频讲了什么"))
                .thenReturn(new VideoQaToolResult("query_subtitle_by_keyword", "找到 1 条相关片段", List.of(citation)));
        when(toolService.loadVideoSegments(mediaFile))
                .thenReturn(new VideoQaToolResult("load_video_segments", "加载视频前 1 个文本片段", List.of(citation)));
        when(toolService.queryTaskStatus(9L, 3L))
                .thenReturn(new VideoQaToolResult("query_task_status", "最近任务状态：SUCCESS", List.of()));
        when(deepSeekUtils.answerQuestion(eq("视频讲了什么"), any(), any()))
                .thenReturn("视频主要讲报销流程。");
        doThrow(new RuntimeException("Data too long"))
                .when(historyRepository)
                .saveMessage(eq("conversation-1"), eq(3L), eq(9L), eq("ASSISTANT"), any(), anyList(), anyList());

        VideoQaService service = new VideoQaService(
                mediaFileMapper,
                toolService,
                deepSeekUtils,
                new ObjectMapper(),
                memoryService,
                historyRepository
        );

        VideoQaResponse response = service.answer(
                9L,
                3L,
                new VideoQaRequest("conversation-1", "视频讲了什么")
        );

        assertThat(response.answer()).isEqualTo("视频主要讲报销流程。");
        assertThat(response.citations()).hasSize(2);
    }
    @Test
    void returnsAnswerWhenToolLookupFails() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(9L);
        mediaFile.setUserId(3L);
        mediaFile.setTranscriptText("[00:00:10] expense process");

        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        VideoQaToolService toolService = mock(VideoQaToolService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        VideoQaMemoryService memoryService = mock(VideoQaMemoryService.class);
        VideoQaHistoryRepository historyRepository = mock(VideoQaHistoryRepository.class);

        when(mediaFileMapper.selectById(9L)).thenReturn(mediaFile);
        when(memoryService.loadRecent(3L, 9L, "conversation-1")).thenReturn(List.of());
        VideoQaCitation citation = new VideoQaCitation(10000L, "ASR", "expense process");
        when(toolService.querySubtitleByKeyword(mediaFile, "what is this about"))
                .thenReturn(new VideoQaToolResult("query_subtitle_by_keyword", "found", List.of(citation)));
        when(toolService.loadVideoSegments(mediaFile))
                .thenReturn(new VideoQaToolResult("load_video_segments", "loaded", List.of(citation)));
        when(toolService.queryTaskStatus(9L, 3L))
                .thenThrow(new RuntimeException("analysis task table unavailable"));
        when(deepSeekUtils.answerQuestion(eq("what is this about"), any(), any()))
                .thenReturn("It is about the expense process.");

        VideoQaService service = new VideoQaService(
                mediaFileMapper,
                toolService,
                deepSeekUtils,
                new ObjectMapper(),
                memoryService,
                historyRepository
        );

        VideoQaResponse response = service.answer(
                9L,
                3L,
                new VideoQaRequest("conversation-1", "what is this about")
        );

        assertThat(response.answer()).isEqualTo("It is about the expense process.");
        assertThat(response.citations()).hasSize(2);
    }
    @Test
    void returnsAnswerWhenMemoryAppendOverflowsStack() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(9L);
        mediaFile.setUserId(3L);
        mediaFile.setTranscriptText("[00:00:10] expense process");

        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        VideoQaToolService toolService = mock(VideoQaToolService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        VideoQaMemoryService memoryService = mock(VideoQaMemoryService.class);
        VideoQaHistoryRepository historyRepository = mock(VideoQaHistoryRepository.class);

        when(mediaFileMapper.selectById(9L)).thenReturn(mediaFile);
        when(memoryService.loadRecent(3L, 9L, "conversation-1")).thenReturn(List.of());
        VideoQaCitation citation = new VideoQaCitation(10000L, "ASR", "expense process");
        when(toolService.querySubtitleByKeyword(mediaFile, "what is this about"))
                .thenReturn(new VideoQaToolResult("query_subtitle_by_keyword", "found", List.of(citation)));
        when(toolService.loadVideoSegments(mediaFile))
                .thenReturn(new VideoQaToolResult("load_video_segments", "loaded", List.of(citation)));
        when(toolService.queryTaskStatus(9L, 3L))
                .thenReturn(new VideoQaToolResult("query_task_status", "SUCCESS", List.of()));
        when(deepSeekUtils.answerQuestion(eq("what is this about"), any(), any()))
                .thenReturn("It is about the expense process.");
        doThrow(new StackOverflowError("redis pExpire recursion"))
                .when(memoryService)
                .appendPair(3L, 9L, "conversation-1", "what is this about", "It is about the expense process.");

        VideoQaService service = new VideoQaService(
                mediaFileMapper,
                toolService,
                deepSeekUtils,
                new ObjectMapper(),
                memoryService,
                historyRepository
        );

        VideoQaResponse response = service.answer(
                9L,
                3L,
                new VideoQaRequest("conversation-1", "what is this about")
        );

        assertThat(response.answer()).isEqualTo("It is about the expense process.");
        assertThat(response.citations()).hasSize(2);
    }
}
