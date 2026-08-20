package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.OperationMetricsService;
import com.example.server.service.OperationalAlertService;
import com.example.server.dto.LibraryQaCitationView;
import com.example.server.dto.LibraryQaRequest;
import com.example.server.dto.LibraryQaResponse;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.service.RagIndexService;
import com.example.server.service.RagSearchResult;
import com.example.server.service.RagSearchService;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LibraryQaService {

    private final MediaFileMapper mediaFileMapper;
    private final RagIndexService ragIndexService;
    private final RagSearchService ragSearchService;
    private final DeepSeekUtils deepSeekUtils;
    private final ObjectMapper objectMapper;
    private final OperationMetricsService metricsService;
    private final OperationalAlertService alertService;
    private final AgentCheckpointService checkpointService;
    private final LongVideoContextService longVideoContextService;

    @Autowired
    public LibraryQaService(MediaFileMapper mediaFileMapper,
                            RagIndexService ragIndexService,
                            RagSearchService ragSearchService,
                            DeepSeekUtils deepSeekUtils,
                            ObjectMapper objectMapper,
                            OperationMetricsService metricsService,
                            OperationalAlertService alertService,
                            AgentCheckpointService checkpointService,
                            LongVideoContextService longVideoContextService) {
        this.mediaFileMapper = mediaFileMapper;
        this.ragIndexService = ragIndexService;
        this.ragSearchService = ragSearchService;
        this.deepSeekUtils = deepSeekUtils;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.alertService = alertService;
        this.checkpointService = checkpointService;
        this.longVideoContextService = longVideoContextService;
    }

    LibraryQaService(MediaFileMapper mediaFileMapper,
                     RagIndexService ragIndexService,
                     RagSearchService ragSearchService,
                     DeepSeekUtils deepSeekUtils,
                     ObjectMapper objectMapper) {
        this(
                mediaFileMapper,
                ragIndexService,
                ragSearchService,
                deepSeekUtils,
                objectMapper,
                new OperationMetricsService(new CompositeMeterRegistry()),
                new OperationalAlertService(),
                null,
                null
        );
    }

    public LibraryQaResponse answer(Long userId, LibraryQaRequest request) {
        Instant startedAt = Instant.now();
        try {
            LibraryQaResponse response = doAnswer(userId, request);
            metricsService.increment("qa.library", "success");
            metricsService.recordDuration("qa.library", "success", Duration.between(startedAt, Instant.now()));
            return response;
        } catch (RuntimeException exception) {
            metricsService.increment("qa.library", "failure");
            metricsService.recordDuration("qa.library", "failure", Duration.between(startedAt, Instant.now()));
            alertService.warn("qa.library.failure", userId, null, exception.getMessage());
            throw exception;
        }
    }

    private LibraryQaResponse doAnswer(Long userId, LibraryQaRequest request) {
        List<MediaFile> mediaFiles = loadOwnedMedia(userId, request.mediaIds());
        if (mediaFiles.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "没有可问答的视频");
        }
        List<Long> mediaIds = mediaFiles.stream().map(MediaFile::getId).toList();
        if (checkpointService != null && longVideoContextService != null) {
            List<MediaEvidence> evidence = mediaIds.stream()
                    .flatMap(mediaId -> {
                        var context = checkpointService.loadContext(mediaId);
                        if (context == null) {
                            return java.util.stream.Stream.empty();
                        }
                        return longVideoContextService.searchEvidence(
                                        mediaId,
                                        new VideoContext(context.source(), request.question(), context.segments()))
                                .stream()
                                .map(hit -> new MediaEvidence(mediaId, hit));
                    })
                    .limit(5)
                    .toList();
            List<LibraryQaCitationView> citations = evidence.stream()
                    .map(item -> new LibraryQaCitationView(
                            item.mediaId(), null, item.hit().startMs(), item.hit().endMs(),
                            item.hit().source(), item.hit().snippet()))
                    .toList();
            String answer = citations.isEmpty()
                    ? "当前视频资料库中没有检索到足够相关的片段。"
                    : deepSeekUtils.answerQuestion(request.question(), toJson(evidence), "[]");
            String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                    ? UUID.randomUUID().toString() : request.conversationId();
            return new LibraryQaResponse(conversationId, answer, citations);
        }
        mediaIds.forEach(mediaId -> ragIndexService.ensureIndexed(userId, mediaId));
        List<RagSearchResult> searchResults = ragSearchService.search(userId, mediaIds, request.question(), 5);
        List<LibraryQaCitationView> citations = toCitations(searchResults, mediaFiles);
        String answer = citations.isEmpty()
                ? "当前视频资料库中没有检索到足够相关的片段。"
                : deepSeekUtils.answerQuestion(request.question(), toJson(searchResults), "[]");
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        return new LibraryQaResponse(conversationId, answer, citations);
    }

    private List<MediaFile> loadOwnedMedia(Long userId, Collection<Long> requestedMediaIds) {
        QueryWrapper<MediaFile> query = new QueryWrapper<MediaFile>()
                .eq("user_id", userId)
                .orderByDesc("upload_time");
        if (requestedMediaIds != null && !requestedMediaIds.isEmpty()) {
            query.in("id", requestedMediaIds);
        }
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(query);
        if (requestedMediaIds != null && !requestedMediaIds.isEmpty() && mediaFiles.size() != requestedMediaIds.size()) {
            throw new AccessDeniedException("存在无权访问或不存在的视频");
        }
        return mediaFiles;
    }

    private List<LibraryQaCitationView> toCitations(List<RagSearchResult> searchResults, List<MediaFile> mediaFiles) {
        Map<Long, MediaFile> mediaById = mediaFiles.stream()
                .collect(Collectors.toMap(MediaFile::getId, Function.identity()));
        return searchResults.stream()
                .map(result -> {
                    MediaFile mediaFile = mediaById.get(result.mediaId());
                    return new LibraryQaCitationView(
                            result.mediaId(),
                            mediaFile == null ? null : mediaFile.getFilename(),
                            result.startMs(),
                            result.endMs(),
                            result.source(),
                            result.content()
                    );
                })
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("问答上下文序列化失败", e);
        }
    }

    private record MediaEvidence(Long mediaId, VideoEvidenceHit hit) {
    }
}
