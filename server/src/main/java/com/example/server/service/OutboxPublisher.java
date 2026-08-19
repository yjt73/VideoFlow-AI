package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.task.domain.OutboxEvent;
import com.example.server.task.domain.OutboxStatus;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.service.AnalysisTaskStateService;
import com.example.server.service.TaskProgressUpdate;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;

/** 周期发布待发送的 Outbox 事件，并保留失败事件供下次重试。 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long[] RETRY_DELAYS_SECONDS = {1, 5, 30, 60, 300};

    private final OutboxEventMapper eventMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final AnalysisTaskStateService stateService;
    private final int batchSize;
    private final int retentionDays;

    public OutboxPublisher(
            OutboxEventMapper eventMapper,
            RocketMQTemplate rocketMQTemplate,
            AnalysisTaskStateService stateService,
            @Value("${analysis.outbox.batch-size:50}") int batchSize,
            @Value("${analysis.outbox.retention-days:7}") int retentionDays
    ) {
        this.eventMapper = eventMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.stateService = stateService;
        this.batchSize = batchSize;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${analysis.outbox.publish-delay-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<OutboxEvent>()
                        .eq(OutboxEvent::getStatus, OutboxStatus.PENDING)
                        .le(OutboxEvent::getNextAttemptAt, LocalDateTime.now())
                        .orderByAsc(OutboxEvent::getId)
                        .last("LIMIT " + Math.max(1, batchSize))
        );
        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    @Scheduled(
            fixedDelayString =
                    "${analysis.outbox.cleanup-delay-ms:86400000}"
    )
    public void cleanupPublishedEvents() {
        eventMapper.delete(
                new LambdaQueryWrapper<OutboxEvent>()
                        .eq(OutboxEvent::getStatus, OutboxStatus.PUBLISHED)
                        .lt(
                                OutboxEvent::getPublishedAt,
                                LocalDateTime.now().minusDays(retentionDays)
                        )
        );
    }

    private void publish(OutboxEvent event) {
        try {
            rocketMQTemplate.syncSend(destination(event), event.getPayload());
            stateService.transition(
                    event.getAggregateId(),
                    Set.of(PENDING),
                    QUEUED,
                    TaskProgressUpdate.progress(5, "QUEUED")
            );
            LocalDateTime now = LocalDateTime.now();
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(now);
            event.setUpdatedAt(now);
            event.setLastError(null);
            eventMapper.updateById(event);
        } catch (Exception exception) {
            int attempts = valueOrZero(event.getPublishAttempts()) + 1;
            event.setPublishAttempts(attempts);
            event.setLastError(truncate(exception.getMessage(), 1000));
            event.setNextAttemptAt(
                    LocalDateTime.now().plusSeconds(retryDelay(attempts))
            );
            event.setUpdatedAt(LocalDateTime.now());
            eventMapper.updateById(event);
            log.warn(
                    "event=outbox_publish_failed taskId={} attempts={} error={}",
                    event.getAggregateId(),
                    attempts,
                    event.getLastError()
            );
        }
    }

    private String destination(OutboxEvent event) {
        if (event.getTag() == null || event.getTag().isBlank()) {
            return event.getTopic();
        }
        return event.getTopic() + ":" + event.getTag();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long retryDelay(int attempts) {
        int index = Math.min(
                Math.max(attempts - 1, 0),
                RETRY_DELAYS_SECONDS.length - 1
        );
        return RETRY_DELAYS_SECONDS[index];
    }

    private String truncate(String value, int maximumLength) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }
}
