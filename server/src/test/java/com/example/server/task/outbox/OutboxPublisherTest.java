package com.example.server.task.outbox;

import com.example.server.task.domain.OutboxEvent;
import com.example.server.task.domain.OutboxStatus;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.task.service.AnalysisTaskStateService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private OutboxEventMapper eventMapper;
    private RocketMQTemplate rocketMQTemplate;
    private AnalysisTaskStateService stateService;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        eventMapper = mock(OutboxEventMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        stateService = mock(AnalysisTaskStateService.class);
        publisher = new OutboxPublisher(
                eventMapper,
                rocketMQTemplate,
                stateService,
                50
        );
    }

    @Test
    void marksTheEventPublishedAndQueuesTheTaskAfterMqAcceptsIt() {
        OutboxEvent event = pendingEvent();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend("video-analysis-topic", event.getPayload()))
                .thenReturn(mock(SendResult.class));
        when(stateService.transition(
                eq("task-1"),
                eq(java.util.Set.of(PENDING)),
                eq(QUEUED),
                any()
        )).thenReturn(true);

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(eventMapper).updateById(event);
    }

    @Test
    void keepsTheEventPendingAndSchedulesAnotherAttemptWhenMqFails() {
        OutboxEvent event = pendingEvent();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend("video-analysis-topic", event.getPayload()))
                .thenThrow(new IllegalStateException("broker unavailable"));
        LocalDateTime before = LocalDateTime.now();

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        verify(eventMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(captor.getValue().getPublishAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getLastError())
                .contains("broker unavailable");
        assertThat(captor.getValue().getNextAttemptAt()).isAfter(before);
    }

    private OutboxEvent pendingEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setAggregateId("task-1");
        event.setTopic("video-analysis-topic");
        event.setPayload("{\"taskId\":\"task-1\"}");
        event.setStatus(OutboxStatus.PENDING);
        event.setPublishAttempts(0);
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        return event;
    }
}
