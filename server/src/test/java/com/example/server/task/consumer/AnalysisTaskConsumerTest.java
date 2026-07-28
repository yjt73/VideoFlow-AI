package com.example.server.task.consumer;

import com.example.server.service.AiService;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.mq.AnalysisTaskMessage;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.service.AnalysisTaskStateService;
import com.example.server.task.service.TaskFailureRecorder;
import com.example.server.task.service.AnalysisTaskCompletionService;
import com.example.server.task.service.AiAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskConsumerTest {

    private AiService aiService;
    private AnalysisTaskMapper taskMapper;
    private AnalysisTaskStateService stateService;
    private TaskFailureRecorder failureRecorder;
    private AnalysisTaskCompletionService completionService;
    private RedissonClient redissonClient;
    private RLock lock;
    private AnalysisTaskConsumer consumer;

    @BeforeEach
    void setUp() throws InterruptedException {
        aiService = mock(AiService.class);
        taskMapper = mock(AnalysisTaskMapper.class);
        stateService = mock(AnalysisTaskStateService.class);
        failureRecorder = mock(TaskFailureRecorder.class);
        completionService = mock(AnalysisTaskCompletionService.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock("lock:analysis:task:task-1"))
                .thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stateService.transition(
                eq("task-1"),
                any(),
                any(),
                any()
        )).thenReturn(true);
        consumer = new AnalysisTaskConsumer(
                new ObjectMapper(),
                aiService,
                taskMapper,
                stateService,
                failureRecorder,
                completionService,
                redissonClient,
                new TaskExceptionClassifier()
        );
    }

    @Test
    void locksByTaskIdAndCompletesEligibleTaskSynchronously() {
        when(taskMapper.selectOne(any())).thenReturn(task(QUEUED));
        AiAnalysisResult result = new AiAnalysisResult("text", "summary");
        when(aiService.analyze(9L, "总结重点")).thenReturn(result);

        consumer.onMessage(jsonMessage());

        verify(aiService).analyze(9L, "总结重点");
        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(QUEUED, AnalysisTaskStatus.RETRYING)),
                eq(RUNNING),
                any()
        );
        verify(completionService).complete("task-1", 9L, result);
        verify(lock).unlock();
    }

    @Test
    void ignoresDuplicateMessagesAfterTheTaskReachedATerminalState() {
        when(taskMapper.selectOne(any())).thenReturn(task(SUCCESS));

        consumer.onMessage(jsonMessage());

        verify(aiService, never()).analyze(any(), any());
    }

    @Test
    void marksRetryingAndRethrowsRetryableFailuresToRocketMq() {
        when(taskMapper.selectOne(any())).thenReturn(task(QUEUED));
        doThrow(new DependencyUnavailableException("AI unavailable"))
                .when(aiService).analyze(9L, "总结重点");

        assertThatThrownBy(() -> consumer.onMessage(jsonMessage()))
                .isInstanceOf(RetryableTaskException.class);

        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(RUNNING)),
                eq(AnalysisTaskStatus.RETRYING),
                any()
        );
        verify(lock).unlock();
        verify(failureRecorder).record(
                eq("task-1"),
                eq("RETRYABLE"),
                eq(1),
                eq("RETRYABLE_ERROR"),
                any(),
                eq(jsonMessage())
        );
    }

    @Test
    void marksPermanentFailuresWithoutRequestingMqRetry() {
        when(taskMapper.selectOne(any())).thenReturn(task(QUEUED));
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "invalid goal"))
                .when(aiService).analyze(9L, "总结重点");

        consumer.onMessage(jsonMessage());

        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(RUNNING)),
                eq(AnalysisTaskStatus.FAILED),
                any()
        );
        verify(failureRecorder).record(
                eq("task-1"),
                eq("PERMANENT"),
                eq(0),
                eq("PERMANENT_ERROR"),
                any(),
                eq(jsonMessage())
        );
    }

    private AnalysisTask task(AnalysisTaskStatus status) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setMediaId(9L);
        task.setStatus(status);
        task.setVersion(0L);
        return task;
    }

    private String jsonMessage() {
        try {
            return new ObjectMapper().writeValueAsString(
                    new AnalysisTaskMessage(
                            "task-1",
                            9L,
                            "SUMMARY",
                            "总结重点"
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
