package com.example.server.task.service;

import com.example.server.task.domain.AnalysisTaskFailure;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskFailureRecorderTest {

    @Test
    void storesAReadableFailureHistoryEntry() {
        AnalysisTaskFailureMapper mapper =
                mock(AnalysisTaskFailureMapper.class);
        TaskFailureRecorder recorder = new TaskFailureRecorder(mapper);

        recorder.record(
                "task-1",
                "RETRYABLE",
                2,
                "AI_TIMEOUT",
                new IllegalStateException("timeout"),
                "{\"taskId\":\"task-1\"}"
        );

        ArgumentCaptor<AnalysisTaskFailure> captor =
                ArgumentCaptor.forClass(AnalysisTaskFailure.class);
        verify(mapper).insert(captor.capture());
        AnalysisTaskFailure failure = captor.getValue();
        assertThat(failure.getTaskId()).isEqualTo("task-1");
        assertThat(failure.getFailureType()).isEqualTo("RETRYABLE");
        assertThat(failure.getRetryCount()).isEqualTo(2);
        assertThat(failure.getErrorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(failure.getErrorMessage()).isEqualTo("timeout");
        assertThat(failure.getStackTrace())
                .contains("IllegalStateException: timeout");
        assertThat(failure.getRawMessage()).contains("task-1");
    }
}
