package com.example.server.service;

import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.dto.AnalysisTaskMessage;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.service.AnalysisTaskStateService;
import com.example.server.service.TaskFailureRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskDeadLetterConsumerTest {

    @Test
    void marksExhaustedMessagesDeadAndStoresTheOriginalPayload()
            throws Exception {
        AnalysisTaskMapper taskMapper = mock(AnalysisTaskMapper.class);
        AnalysisTaskStateService stateService =
                mock(AnalysisTaskStateService.class);
        TaskFailureRecorder failureRecorder =
                mock(TaskFailureRecorder.class);
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setStatus(RETRYING);
        task.setRetryCount(3);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);
        AnalysisTaskDeadLetterConsumer consumer =
                new AnalysisTaskDeadLetterConsumer(
                        new ObjectMapper(),
                        taskMapper,
                        stateService,
                        failureRecorder
                );
        String payload = new ObjectMapper().writeValueAsString(
                new AnalysisTaskMessage(
                        "task-1",
                        9L,
                        "VIDEO_ANALYSIS",
                        "总结重点"
                )
        );

        consumer.onMessage(payload);

        verify(stateService).transition(
                eq("task-1"),
                eq(Set.of(
                        AnalysisTaskStatus.QUEUED,
                        AnalysisTaskStatus.RUNNING,
                        RETRYING
                )),
                eq(DEAD),
                any()
        );
        verify(failureRecorder).record(
                eq("task-1"),
                eq("DEAD_LETTER"),
                eq(3),
                eq("RETRY_EXHAUSTED"),
                any(),
                eq(payload)
        );
    }
}
