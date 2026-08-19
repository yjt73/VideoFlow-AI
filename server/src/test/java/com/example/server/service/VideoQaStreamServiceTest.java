package com.example.server.service;

import com.example.server.dto.VideoQaCitationView;
import com.example.server.dto.VideoQaRequest;
import com.example.server.dto.VideoQaResponse;
import com.example.server.dto.VideoQaStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoQaStreamServiceTest {

    @Test
    void buildsOrderedStageEventsAroundQaAnswer() {
        VideoQaService videoQaService = mock(VideoQaService.class);
        VideoQaStreamService streamService = new VideoQaStreamService(videoQaService);
        VideoQaRequest request = new VideoQaRequest(null, "Redis 挂了怎么办");
        when(videoQaService.answer(9L, 3L, request))
                .thenReturn(new VideoQaResponse(
                        "conversation-1",
                        "Redis 不可用时暂停上传。",
                        List.of(new VideoQaCitationView(0L, "TRANSCRIPT", "Redis 不可用"))
                ));

        List<VideoQaStreamEvent> events = streamService.buildEvents(9L, 3L, request);

        assertThat(events).extracting(VideoQaStreamEvent::type)
                .containsExactly("retrieving", "context_ready", "model_calling", "answer", "done");
        assertThat(events.get(3).data()).isInstanceOf(VideoQaResponse.class);
    }
}
