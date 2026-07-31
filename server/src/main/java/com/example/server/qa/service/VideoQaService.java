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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class VideoQaService {

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
        historyRepository.createConversation(
                conversationId,
                userId,
                mediaId,
                request.question().length() > 50 ? request.question().substring(0, 50) : request.question()
        );
        List<VideoQaMemoryMessage> history = memoryService.loadRecent(userId, mediaId, conversationId);
        List<VideoQaToolResult> toolResults = List.of(
                toolService.querySubtitleByKeyword(mediaFile, request.question()),
                toolService.loadVideoSegments(mediaFile),
                toolService.queryTaskStatus(mediaId, userId)
        );
        List<VideoQaCitation> citations = toolResults.stream()
                .flatMap(result -> result.citations().stream())
                .limit(5)
                .toList();
        String answer = deepSeekUtils.answerQuestion(request.question(), toJson(toolResults), toJson(history));
        if (answer == null || answer.isBlank()) {
            answer = citations.isEmpty()
                    ? "当前视频中没有检索到足够相关的信息。"
                    : citations.get(0).content();
        }
        historyRepository.saveMessage(conversationId, userId, mediaId, "USER", request.question(), List.of(), List.of());
        historyRepository.saveMessage(conversationId, userId, mediaId, "ASSISTANT", answer, citations, toolResults);
        memoryService.appendPair(userId, mediaId, conversationId, request.question(), answer);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("问答上下文序列化失败", e);
        }
    }
}
