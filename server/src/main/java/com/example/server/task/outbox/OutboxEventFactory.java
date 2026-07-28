package com.example.server.task.outbox;

import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.OutboxEvent;
import com.example.server.task.domain.OutboxStatus;
import com.example.server.task.mq.AnalysisTaskMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/** 将业务任务转换为可可靠投递的 Outbox 事件。 */
@Component
public class OutboxEventFactory {

    public static final String ANALYSIS_TOPIC = "video-analysis-topic";

    private final ObjectMapper objectMapper;

    public OutboxEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEvent analysisRequested(AnalysisTask task) {
        return requested(task, "ANALYSIS_REQUESTED_0");
    }

    public OutboxEvent manualRetryRequested(AnalysisTask task) {
        return requested(
                task,
                "ANALYSIS_MANUAL_RETRY_" + UUID.randomUUID()
        );
    }

    private OutboxEvent requested(AnalysisTask task, String eventType) {
        AnalysisTaskMessage message = new AnalysisTaskMessage(
                task.getTaskId(),
                task.getMediaId(),
                task.getTaskType().name(),
                task.getUserGoal()
        );
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateId(task.getTaskId());
        event.setEventType(eventType);
        event.setTopic(ANALYSIS_TOPIC);
        event.setPayload(toJson(message));
        event.setStatus(OutboxStatus.PENDING);
        event.setPublishAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        return event;
    }

    private String toJson(AnalysisTaskMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化分析任务消息", exception);
        }
    }
}
