package com.example.server.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.rag.persistence.RagSegment;
import com.example.server.rag.persistence.RagSegmentRepository;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final MediaFileMapper mediaFileMapper;
    private final RagSegmentRepository ragSegmentRepository;
    private final RagSegmenter ragSegmenter;
    private final RagChunker ragChunker;
    private final EmbeddingUtils embeddingUtils;
    private final EmbeddingCacheService embeddingCacheService;
    private final ObjectMapper objectMapper;

    public RagIndexService(MediaFileMapper mediaFileMapper,
                           RagSegmentRepository ragSegmentRepository,
                           RagSegmenter ragSegmenter,
                           RagChunker ragChunker,
                           EmbeddingUtils embeddingUtils,
                           EmbeddingCacheService embeddingCacheService,
                           ObjectMapper objectMapper) {
        this.mediaFileMapper = mediaFileMapper;
        this.ragSegmentRepository = ragSegmentRepository;
        this.ragSegmenter = ragSegmenter;
        this.ragChunker = ragChunker;
        this.embeddingUtils = embeddingUtils;
        this.embeddingCacheService = embeddingCacheService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ensureIndexed(Long userId, Long mediaId) {
        if (ragSegmentRepository.existsChunkForMedia(userId, mediaId)) {
            return;
        }
        rebuild(userId, mediaId);
    }

    @Transactional
    public void rebuild(Long userId, Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (!userId.equals(mediaFile.getUserId())) {
            throw new AccessDeniedException("无权访问该视频");
        }
        ragSegmentRepository.deleteByMedia(userId, mediaId);
        List<RagTextSegment> sourceSegments = new ArrayList<>();
        sourceSegments.addAll(ragSegmenter.splitTranscript(mediaFile.getTranscriptText()));
        sourceSegments.addAll(ragSegmenter.splitSummary(mediaFile.getAiSummary()));
        List<RagSegment> segments = toEntities(
                userId,
                mediaId,
                ragChunker.chunk(sourceSegments)
        );
        for (RagSegment segment : segments) {
            ragSegmentRepository.insert(segment);
        }
    }

    private List<RagSegment> toEntities(Long userId, Long mediaId, List<RagChunk> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    RagChunk chunk = chunks.get(index);
                    RagSegment segment = new RagSegment();
                    segment.setUserId(userId);
                    segment.setMediaId(mediaId);
                    segment.setSegmentIndex(index);
                    segment.setStartMs(chunk.startMs());
                    segment.setEndMs(chunk.endMs());
                    segment.setSource("VIDEO_CHUNK");
                    segment.setContent(chunk.content());
                    segment.setTokenText(normalize(chunk.content()));
                    embedding(chunk.content()).ifPresent(value -> segment.setEmbeddingJson(value));
                    return segment;
                })
                .toList();
    }

    private Optional<String> embedding(String content) {
        try {
            String model = embeddingUtils.model();
            List<Double> vector = embeddingCacheService.find(content, model)
                    .orElseGet(() -> {
                        List<Double> generated = embeddingUtils.embed(content);
                        embeddingCacheService.save(content, model, generated);
                        return generated;
                    });
            return Optional.of(objectMapper.writeValueAsString(vector));
        } catch (JsonProcessingException exception) {
            log.warn("event=rag_embedding_serialize_failed", exception);
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("event=rag_embedding_fallback contentLength={}", content.length(), exception);
            return Optional.empty();
        }
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }
}
