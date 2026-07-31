package com.example.server.rag.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.rag.persistence.RagSegment;
import com.example.server.rag.persistence.RagSegmentRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RagIndexService {

    private final MediaFileMapper mediaFileMapper;
    private final RagSegmentRepository ragSegmentRepository;
    private final RagSegmenter ragSegmenter;

    public RagIndexService(MediaFileMapper mediaFileMapper,
                           RagSegmentRepository ragSegmentRepository,
                           RagSegmenter ragSegmenter) {
        this.mediaFileMapper = mediaFileMapper;
        this.ragSegmentRepository = ragSegmentRepository;
        this.ragSegmenter = ragSegmenter;
    }

    @Transactional
    public void ensureIndexed(Long userId, Long mediaId) {
        if (ragSegmentRepository.existsForMedia(userId, mediaId)) {
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
        List<RagSegment> segments = new ArrayList<>();
        segments.addAll(toEntities(userId, mediaId, ragSegmenter.splitTranscript(mediaFile.getTranscriptText())));
        segments.addAll(toEntities(userId, mediaId, ragSegmenter.splitSummary(mediaFile.getAiSummary())));
        for (RagSegment segment : segments) {
            ragSegmentRepository.insert(segment);
        }
    }

    private List<RagSegment> toEntities(Long userId, Long mediaId, List<RagTextSegment> textSegments) {
        return textSegments.stream()
                .map(textSegment -> {
                    RagSegment segment = new RagSegment();
                    segment.setUserId(userId);
                    segment.setMediaId(mediaId);
                    segment.setSegmentIndex(textSegment.segmentIndex());
                    segment.setStartMs(textSegment.startMs());
                    segment.setEndMs(textSegment.endMs());
                    segment.setSource(textSegment.source());
                    segment.setContent(textSegment.content());
                    segment.setTokenText(normalize(textSegment.content()));
                    return segment;
                })
                .toList();
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }
}
