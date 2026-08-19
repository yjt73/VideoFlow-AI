package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.dto.AnalysisTaskMessage;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.service.AnalysisTaskStateService;
import com.example.server.service.TaskFailureRecorder;
import com.example.server.service.TaskProgressUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.CANCELLED;
import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.FAILED;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;

/** 消费 RocketMQ 死信并保存可人工排查的失败详情。 */
@Component
@RocketMQMessageListener(
        topic = "%DLQ%video-analysis-v3-group",
        consumerGroup = "video-analysis-v3-dlq-handler"
)
public class AnalysisTaskDeadLetterConsumer
        implements RocketMQListener<String> {

    private static final Logger log =
            LoggerFactory.getLogger(AnalysisTaskDeadLetterConsumer.class);
    private static final Set<AnalysisTaskStatus> DEAD_ELIGIBLE =
            Set.of(QUEUED, RUNNING, RETRYING);
    private static final Set<AnalysisTaskStatus> TERMINAL =
            Set.of(SUCCESS, FAILED, DEAD, CANCELLED);

    private final ObjectMapper objectMapper;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisTaskStateService stateService;
    private final TaskFailureRecorder failureRecorder;

    public AnalysisTaskDeadLetterConsumer(
            ObjectMapper objectMapper,
            AnalysisTaskMapper taskMapper,
            AnalysisTaskStateService stateService,
            TaskFailureRecorder failureRecorder
    ) {
        this.objectMapper = objectMapper;
        this.taskMapper = taskMapper;
        this.stateService = stateService;
        this.failureRecorder = failureRecorder;
    }

    @Override
    public void onMessage(String payload) {
        AnalysisTaskMessage message = parse(payload);
        if (message == null || message.taskId() == null) {
            log.error("event=analysis_dlq_invalid payload={}", payload);
            return;
        }
        AnalysisTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getTaskId, message.taskId())
        );
        if (task == null || TERMINAL.contains(task.getStatus())) {
            return;
        }

        IllegalStateException exhausted = new IllegalStateException(
                "RocketMQ retries exhausted"
        );
        boolean markedDead = stateService.transition(
                message.taskId(),
                DEAD_ELIGIBLE,
                DEAD,
                TaskProgressUpdate.failure(
                        task.getProgress() == null ? 10 : task.getProgress(),
                        "DEAD_LETTER",
                        "RETRY_EXHAUSTED",
                        exhausted.getMessage()
                )
        );
        if (!markedDead) {
            return;
        }
        int retryCount =
                task.getRetryCount() == null ? 0 : task.getRetryCount();
        failureRecorder.record(
                message.taskId(),
                "DEAD_LETTER",
                retryCount,
                "RETRY_EXHAUSTED",
                exhausted,
                payload
        );
        log.error(
                "event=analysis_task_dead taskId={} mediaId={} "
                        + "retryCount={} action=manual_intervention_required",
                message.taskId(),
                message.mediaId(),
                retryCount
        );
    }

    private AnalysisTaskMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, AnalysisTaskMessage.class);
        } catch (JsonProcessingException exception) {
            log.error(
                    "event=analysis_dlq_parse_failed payload={}",
                    payload,
                    exception
            );
            return null;
        }
    }
}
