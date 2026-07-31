package com.example.server.qa.service;

import com.example.server.qa.api.VideoQaRequest;
import com.example.server.qa.api.VideoQaResponse;
import com.example.server.qa.api.VideoQaStreamEvent;
import com.example.server.observability.OperationMetricsService;
import com.example.server.observability.OperationalAlertService;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class VideoQaStreamService {

    private final VideoQaService videoQaService;
    private final OperationMetricsService metricsService;
    private final OperationalAlertService alertService;

    @Autowired
    public VideoQaStreamService(VideoQaService videoQaService,
                                OperationMetricsService metricsService,
                                OperationalAlertService alertService) {
        this.videoQaService = videoQaService;
        this.metricsService = metricsService;
        this.alertService = alertService;
    }

    public VideoQaStreamService(VideoQaService videoQaService) {
        this.videoQaService = videoQaService;
        this.metricsService = new OperationMetricsService(new CompositeMeterRegistry());
        this.alertService = new OperationalAlertService();
    }

    public List<VideoQaStreamEvent> buildEvents(Long mediaId, Long userId, VideoQaRequest request) {
        Instant startedAt = Instant.now();
        try {
            List<VideoQaStreamEvent> events = new ArrayList<>();
            events.add(new VideoQaStreamEvent("retrieving", "开始检索视频片段", null));
            events.add(new VideoQaStreamEvent("context_ready", "问答上下文准备完成", null));
            events.add(new VideoQaStreamEvent("model_calling", "开始调用大模型生成回答", null));
            VideoQaResponse response = videoQaService.answer(mediaId, userId, request);
            events.add(new VideoQaStreamEvent("answer", "回答生成完成", response));
            events.add(new VideoQaStreamEvent("done", "问答流程结束", response.conversationId()));
            metricsService.increment("qa.stream", "success");
            metricsService.recordDuration("qa.stream", "success", Duration.between(startedAt, Instant.now()));
            return events;
        } catch (RuntimeException exception) {
            metricsService.increment("qa.stream", "failure");
            metricsService.recordDuration("qa.stream", "failure", Duration.between(startedAt, Instant.now()));
            alertService.warn("qa.stream.failure", userId, mediaId, exception.getMessage());
            throw exception;
        }
    }
}
