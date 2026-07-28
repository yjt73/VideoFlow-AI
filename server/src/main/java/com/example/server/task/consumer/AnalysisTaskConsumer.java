package com.example.server.task.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.service.AiService;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.mq.AnalysisTaskMessage;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.service.AnalysisTaskStateService;
import com.example.server.task.service.TaskProgressUpdate;
import com.example.server.task.service.TaskFailureRecorder;
import com.example.server.task.service.AnalysisTaskCompletionService;
import com.example.server.task.service.AiAnalysisResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.example.server.task.domain.AnalysisTaskStatus.CANCELLED;
import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.FAILED;
import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;

/** 同步执行单个分析任务，让异常能够正确触发 RocketMQ 重试。 */
@Component
@RocketMQMessageListener(
        topic = "video-analysis-topic",
        consumerGroup = "video-analysis-v3-group",
        consumeThreadNumber = 4,
        consumeThreadMax = 8,
        consumeTimeout = 10,
        awaitTerminationMillisWhenShutdown = 30000,
        maxReconsumeTimes = 3
)
public class AnalysisTaskConsumer implements RocketMQListener<String> {

    private static final Logger log =
            LoggerFactory.getLogger(AnalysisTaskConsumer.class);
    private static final Set<AnalysisTaskStatus> TERMINAL =
            Set.of(SUCCESS, FAILED, DEAD, CANCELLED);

    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisTaskStateService stateService;
    private final TaskFailureRecorder failureRecorder;
    private final AnalysisTaskCompletionService completionService;
    private final RedissonClient redissonClient;
    private final TaskExceptionClassifier exceptionClassifier;

    public AnalysisTaskConsumer(
            ObjectMapper objectMapper,
            AiService aiService,
            AnalysisTaskMapper taskMapper,
            AnalysisTaskStateService stateService,
            TaskFailureRecorder failureRecorder,
            AnalysisTaskCompletionService completionService,
            RedissonClient redissonClient,
            TaskExceptionClassifier exceptionClassifier
    ) {
        this.objectMapper = objectMapper;
        this.aiService = aiService;
        this.taskMapper = taskMapper;
        this.stateService = stateService;
        this.failureRecorder = failureRecorder;
        this.completionService = completionService;
        this.redissonClient = redissonClient;
        this.exceptionClassifier = exceptionClassifier;
    }

    @Override
    public void onMessage(String payload) {
        AnalysisTaskMessage message = parse(payload);
        if (message.taskId() == null || message.mediaId() == null) {
            log.error("event=analysis_message_invalid payload={}", payload);
            return;
        }

        RLock lock = redissonClient.getLock(
                "lock:analysis:task:" + message.taskId()
        );
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
            if (!acquired) {
                log.info(
                        "event=analysis_duplicate_inflight taskId={}",
                        message.taskId()
                );
                return;
            }
            execute(message, payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableTaskException(
                    "Interrupted while acquiring task lock",
                    exception
            );
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void execute(AnalysisTaskMessage message, String rawMessage) {
        AnalysisTask task = findTask(message.taskId());
        if (task == null || TERMINAL.contains(task.getStatus())) {
            return;
        }

        if (task.getStatus() == PENDING) {
            stateService.transition(
                    message.taskId(),
                    Set.of(PENDING),
                    QUEUED,
                    TaskProgressUpdate.progress(5, "QUEUED")
            );
        }
        boolean claimed = stateService.transition(
                message.taskId(),
                Set.of(QUEUED, RETRYING),
                RUNNING,
                TaskProgressUpdate.reset(10, "ANALYZING")
        );
        if (!claimed) {
            return;
        }

        try {
            AiAnalysisResult result =
                    aiService.analyze(message.mediaId(), message.userGoal());
            completionService.complete(
                    message.taskId(),
                    message.mediaId(),
                    result
            );
        } catch (RuntimeException failure) {
            handleFailure(task, failure, rawMessage);
        }
    }

    private void handleFailure(
            AnalysisTask task,
            RuntimeException failure,
            String rawMessage
    ) {
        String taskId = task.getTaskId();
        if (exceptionClassifier.classify(failure)
                == TaskFailureType.PERMANENT) {
            failureRecorder.record(
                    taskId,
                    "PERMANENT",
                    retryCount(task),
                    "PERMANENT_ERROR",
                    failure,
                    rawMessage
            );
            stateService.transition(
                    taskId,
                    Set.of(RUNNING),
                    FAILED,
                    TaskProgressUpdate.failure(
                            10,
                            "FAILED",
                            "PERMANENT_ERROR",
                            safeMessage(failure)
                    )
            );
            return;
        }
        int nextRetryCount = retryCount(task) + 1;
        failureRecorder.record(
                taskId,
                "RETRYABLE",
                nextRetryCount,
                "RETRYABLE_ERROR",
                failure,
                rawMessage
        );
        stateService.transition(
                taskId,
                Set.of(RUNNING),
                RETRYING,
                TaskProgressUpdate.failure(
                        10,
                        "WAITING_RETRY",
                        "RETRYABLE_ERROR",
                        safeMessage(failure)
                )
        );
        throw new RetryableTaskException(
                "Retryable analysis failure for task " + taskId,
                failure
        );
    }

    private int retryCount(AnalysisTask task) {
        return task.getRetryCount() == null ? 0 : task.getRetryCount();
    }

    private AnalysisTask findTask(String taskId) {
        return taskMapper.selectOne(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getTaskId, taskId)
        );
    }

    private AnalysisTaskMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, AnalysisTaskMessage.class);
        } catch (JsonProcessingException exception) {
            log.error(
                    "event=analysis_message_parse_failed payload={}",
                    payload,
                    exception
            );
            throw new PermanentTaskException(
                    "Invalid analysis task message",
                    exception
            );
        }
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }
}
