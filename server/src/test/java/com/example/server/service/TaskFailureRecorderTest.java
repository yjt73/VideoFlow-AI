package com.example.server.service;

import com.example.server.task.persistence.AnalysisTaskMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskFailureRecorderTest {

    @Test
    void storesAReadableFailureHistoryEntry() {
        AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
        TaskFailureRecorder recorder = new TaskFailureRecorder(mapper);

        recorder.record(
                "task-1",
                "RETRYABLE",
                2,
                "AI_TIMEOUT",
                new IllegalStateException("timeout"),
                "{\"taskId\":\"task-1\"}"
        );

        verify(mapper).recordFailure(
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("RETRYABLE"),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq("AI_TIMEOUT"),
                org.mockito.ArgumentMatchers.eq("timeout"),
                org.mockito.ArgumentMatchers.contains("IllegalStateException: timeout"),
                org.mockito.ArgumentMatchers.contains("task-1")
        );
    }
}
