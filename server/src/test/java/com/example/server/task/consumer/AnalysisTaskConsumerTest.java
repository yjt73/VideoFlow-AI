package com.example.server.task.consumer;

import com.example.server.service.AiService;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.mq.AnalysisTaskMessage;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.service.AnalysisTaskStateService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskConsumerTest {

    private AiService aiService;
    private AnalysisTaskMapper taskMapper;
    private AnalysisTaskStateService stateService;
    private RedissonClient redissonClient;
    private RLock lock;
    private AnalysisTaskConsumer consumer;

    @BeforeEach
    void setUp() throws InterruptedException {
        aiService = mock(AiService.class);
        taskMapper = mock(AnalysisTaskMapper.class);
        stateService = mock(AnalysisTaskStateService.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock("lock:analysis:task:task-1"))
                .thenReturn(lock);
        when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
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
                redissonClient,
                new TaskExceptionClassifier()
        );
    }

    @Test
    void locksByTaskIdAndCompletesEligibleTaskSynchronously() {
        when(taskMapper.selectOne(any())).thenReturn(task(QUEUED));

        consumer.onMessage(jsonMessage());

        verify(aiService).analyze(9L, "总结重点");
        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(QUEUED, AnalysisTaskStatus.RETRYING)),
                eq(RUNNING),
                any()
        );
        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(RUNNING)),
                eq(SUCCESS),
                any()
        );
        verify(lock).unlock();
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
