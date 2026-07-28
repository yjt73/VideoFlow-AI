package com.example.server.task.service;

import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStateMachine;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.persistence.AnalysisTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskStateServiceTest {

    private AnalysisTaskMapper mapper;
    private AnalysisTaskStateService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AnalysisTaskMapper.class);
        service = new AnalysisTaskStateService(mapper, new AnalysisTaskStateMachine());
    }

    @Test
    void returnsFalseWhenAnotherWorkerWinsTheOptimisticUpdate() {
        AnalysisTask task = task(AnalysisTaskStatus.QUEUED, 4L);
        when(mapper.selectOne(any())).thenReturn(task);
        when(mapper.transition(
                eq("task-1"),
                eq(4L),
                eq("QUEUED"),
                eq("RUNNING"),
                any()
        )).thenReturn(0);

        boolean transitioned = service.transition(
                "task-1",
                Set.of(AnalysisTaskStatus.QUEUED),
                AnalysisTaskStatus.RUNNING,
                TaskProgressUpdate.progress(10, "ANALYZING")
        );

        assertThat(transitioned).isFalse();
    }

    @Test
    void refusesAnIllegalTransitionBeforeUpdatingTheDatabase() {
        when(mapper.selectOne(any())).thenReturn(task(AnalysisTaskStatus.SUCCESS, 2L));

        boolean transitioned = service.transition(
                "task-1",
                Set.of(AnalysisTaskStatus.SUCCESS),
                AnalysisTaskStatus.RUNNING,
                TaskProgressUpdate.none()
        );

        assertThat(transitioned).isFalse();
        verify(mapper, never()).transition(
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private AnalysisTask task(AnalysisTaskStatus status, long version) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setStatus(status);
        task.setVersion(version);
        return task;
    }
}
