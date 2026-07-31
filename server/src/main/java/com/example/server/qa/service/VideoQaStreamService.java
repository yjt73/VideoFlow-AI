package com.example.server.qa.service;

import com.example.server.qa.api.VideoQaRequest;
import com.example.server.qa.api.VideoQaResponse;
import com.example.server.qa.api.VideoQaStreamEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VideoQaStreamService {

    private final VideoQaService videoQaService;

    public VideoQaStreamService(VideoQaService videoQaService) {
        this.videoQaService = videoQaService;
    }

    public List<VideoQaStreamEvent> buildEvents(Long mediaId, Long userId, VideoQaRequest request) {
        List<VideoQaStreamEvent> events = new ArrayList<>();
        events.add(new VideoQaStreamEvent("retrieving", "开始检索视频片段", null));
        events.add(new VideoQaStreamEvent("context_ready", "问答上下文准备完成", null));
        events.add(new VideoQaStreamEvent("model_calling", "开始调用大模型生成回答", null));
        VideoQaResponse response = videoQaService.answer(mediaId, userId, request);
        events.add(new VideoQaStreamEvent("answer", "回答生成完成", response));
        events.add(new VideoQaStreamEvent("done", "问答流程结束", response.conversationId()));
        return events;
    }
}
