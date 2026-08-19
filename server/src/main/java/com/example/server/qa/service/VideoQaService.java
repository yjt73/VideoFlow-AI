package com.example.server.qa.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.qa.persistence.VideoQaHistoryRepository;
import com.example.server.qa.api.VideoQaCitationView;
import com.example.server.qa.api.VideoQaRequest;
import com.example.server.qa.api.VideoQaResponse;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VideoQaService {

    private static final Logger log = LoggerFactory.getLogger(VideoQaService.class);

    private final MediaFileMapper mediaFileMapper;
    private final VideoQaToolService toolService;
    private final DeepSeekUtils deepSeekUtils;
    private final ObjectMapper objectMapper;
    private final VideoQaMemoryService memoryService;
    private final VideoQaHistoryRepository historyRepository;

    public VideoQaService(MediaFileMapper mediaFileMapper,
                          VideoQaToolService toolService,
                          DeepSeekUtils deepSeekUtils,
                          ObjectMapper objectMapper,
                          VideoQaMemoryService memoryService,
                          VideoQaHistoryRepository historyRepository) {
        this.mediaFileMapper = mediaFileMapper;
        this.toolService = toolService;
        this.deepSeekUtils = deepSeekUtils;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.historyRepository = historyRepository;
    }

    public VideoQaResponse answer(Long mediaId, Long userId, VideoQaRequest request) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (!userId.equals(mediaFile.getUserId())) {
            throw new AccessDeniedException("无权访问该视频");
        }

        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        createConversationBestEffort(conversationId, userId, mediaId, request.question());
        List<VideoQaMemoryMessage> history = loadRecentBestEffort(userId, mediaId, conversationId);
        List<VideoQaToolResult> toolResults = loadToolResultsBestEffort(mediaFile, mediaId, userId, request.question());
        List<VideoQaCitation> citations = toolResults.stream()
                .flatMap(result -> result.citations().stream())
                .limit(5)
                .toList();
        String answer = answerWithModelOrFallback(
                request.question(),
                toolResults,
                history,
                citations
        );
        if (answer == null || answer.isBlank()) {
            answer = citations.isEmpty()
                    ? "当前视频中没有检索到足够相关的信息。"
                    : citations.get(0).content();
        }
        saveHistoryBestEffort(conversationId, userId, mediaId, request.question(), answer, citations, toolResults);
        appendMemoryBestEffort(userId, mediaId, conversationId, request.question(), answer);
        return new VideoQaResponse(
                conversationId,
                answer,
                citations.stream()
                        .map(citation -> new VideoQaCitationView(
                                citation.timestampMs(),
                                citation.source(),
                                citation.content()
                        ))
                        .toList()
        );
    }

    private void createConversationBestEffort(String conversationId, Long userId, Long mediaId, String question) {
        try {
            historyRepository.createConversation(
                    conversationId,
                    userId,
                    mediaId,
                    question.length() > 50 ? question.substring(0, 50) : question
            );
        } catch (RuntimeException exception) {
            log.warn("event=video_qa_conversation_persist_failed mediaId={} userId={} error={}",
                    mediaId, userId, exception.getMessage());
        }
    }

    private List<VideoQaMemoryMessage> loadRecentBestEffort(Long userId, Long mediaId, String conversationId) {
        try {
            return memoryService.loadRecent(userId, mediaId, conversationId);
        } catch (RuntimeException exception) {
            log.warn("event=video_qa_memory_load_failed mediaId={} userId={} error={}",
                    mediaId, userId, exception.getMessage());
            return List.of();
        }
    }

    private String answerWithModelOrFallback(String question,
                                             List<VideoQaToolResult> toolResults,
                                             List<VideoQaMemoryMessage> history,
                                             List<VideoQaCitation> citations) {
        try {
            return deepSeekUtils.answerQuestion(question, toJson(toolResults), toJson(history));
        } catch (RuntimeException exception) {
            if (citations.isEmpty()) {
                return "当前视频中没有检索到足够相关的信息，且 AI 问答服务暂时不可用。";
            }
            return citations.get(0).content();
        }
    }

    private List<VideoQaToolResult> loadToolResultsBestEffort(MediaFile mediaFile,
                                                              Long mediaId,
                                                              Long userId,
                                                              String question) {
        List<VideoQaToolResult> results = new ArrayList<>();
        try {
            results.add(toolService.querySubtitleByKeyword(mediaFile, question));
        } catch (RuntimeException exception) {
            results.add(toolFailed("query_subtitle_by_keyword", mediaId, userId, exception));
        }
        try {
            results.add(toolService.loadVideoSegments(mediaFile));
        } catch (RuntimeException exception) {
            results.add(toolFailed("load_video_segments", mediaId, userId, exception));
        }
        try {
            results.add(toolService.queryTaskStatus(mediaId, userId));
        } catch (RuntimeException exception) {
            results.add(toolFailed("query_task_status", mediaId, userId, exception));
        }
        return results;
    }

    private VideoQaToolResult toolFailed(String toolName, Long mediaId, Long userId, RuntimeException exception) {
        log.warn("event=video_qa_tool_failed tool={} mediaId={} userId={} error={}",
                toolName, mediaId, userId, exception.getMessage());
        return new VideoQaToolResult(toolName, "tool call failed: " + exception.getMessage(), List.of());
    }

    private void saveHistoryBestEffort(String conversationId,
                                       Long userId,
                                       Long mediaId,
                                       String question,
                                       String answer,
                                       List<VideoQaCitation> citations,
                                       List<VideoQaToolResult> toolResults) {
        try {
            historyRepository.saveMessage(conversationId, userId, mediaId, "USER", question, List.of(), List.of());
            historyRepository.saveMessage(conversationId, userId, mediaId, "ASSISTANT", answer, citations, toolResults);
        } catch (RuntimeException exception) {
            log.warn("event=video_qa_history_persist_failed mediaId={} userId={} error={}",
                    mediaId, userId, exception.getMessage());
        }
    }

    private void appendMemoryBestEffort(Long userId,
                                        Long mediaId,
                                        String conversationId,
                                        String question,
                                        String answer) {
        try {
            memoryService.appendPair(userId, mediaId, conversationId, question, answer);
        } catch (Throwable exception) {
            log.warn("event=video_qa_memory_append_failed mediaId={} userId={} error={}",
                    mediaId, userId, exception.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("问答上下文序列化失败", e);
        }
    }
}
