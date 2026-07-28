package com.example.server.task.compensation;

import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.OutboxEvent;
import com.example.server.task.outbox.OutboxEventFactory;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.task.service.AnalysisTaskStateService;
import com.example.server.task.service.TaskFailureRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskCompensatorTest {

    private AnalysisTaskMapper taskMapper;
    private AnalysisTaskStateService stateService;
    private OutboxEventMapper outboxEventMapper;
    private OutboxEventFactory outboxEventFactory;
    private TaskFailureRecorder failureRecorder;
    private AnalysisTaskCompensator compensator;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AnalysisTaskMapper.class);
        stateService = mock(AnalysisTaskStateService.class);
        outboxEventMapper = mock(OutboxEventMapper.class);
        outboxEventFactory = mock(OutboxEventFactory.class);
        failureRecorder = mock(TaskFailureRecorder.class);
        compensator = new AnalysisTaskCompensator(
                taskMapper,
                stateService,
                outboxEventMapper,
                outboxEventFactory,
                failureRecorder,
                30,
                3
        );
    }

    @Test
    void requeuesAStalledTaskWithinTheCompensationLimit() {
        AnalysisTask task = task(AnalysisTaskStatus.QUEUED, 1);
        OutboxEvent event = new OutboxEvent();
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);
        when(taskMapper.incrementCompensation("task-1", 1))
                .thenReturn(1);
        when(outboxEventFactory.compensationRequested(task, 2))
                .thenReturn(event);

        compensator.compensateStalledTasks();

        verify(taskMapper).incrementCompensation("task-1", 1);
        verify(outboxEventMapper).insert(event);
    }

    @Test
    void marksAStalledTaskDeadAfterTheCompensationLimit() {
        AnalysisTask task = task(AnalysisTaskStatus.RETRYING, 3);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);

        compensator.compensateStalledTasks();

        verify(failureRecorder).record(
                eq("task-1"),
                eq("COMPENSATION"),
                eq(0),
                eq("COMPENSATION_EXHAUSTED"),
                any(),
                eq(null)
        );
        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    private AnalysisTask task(
            AnalysisTaskStatus status,
            int compensationCount
    ) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setMediaId(9L);
        task.setStatus(status);
        task.setRetryCount(0);
        task.setCompensationCount(compensationCount);
        task.setVersion(0L);
        task.setProgress(10);
        return task;
    }
}
