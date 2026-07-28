package com.example.server.task.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.task.api.AnalysisTaskCreatedView;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.OutboxEvent;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.task.outbox.OutboxEventFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskServiceTest {

    private MediaFileMapper mediaFileMapper;
    private AnalysisTaskMapper taskMapper;
    private OutboxEventMapper outboxEventMapper;
    private OutboxEventFactory outboxEventFactory;
    private AnalysisTaskStateService stateService;
    private AnalysisTaskService service;

    @BeforeEach
    void setUp() {
        mediaFileMapper = mock(MediaFileMapper.class);
        taskMapper = mock(AnalysisTaskMapper.class);
        outboxEventMapper = mock(OutboxEventMapper.class);
        outboxEventFactory = mock(OutboxEventFactory.class);
        stateService = mock(AnalysisTaskStateService.class);
        service = new AnalysisTaskService(
                mediaFileMapper,
                taskMapper,
                outboxEventMapper,
                outboxEventFactory,
                stateService
        );
    }

    @Test
    void createsAPendingTaskWithAGlobalUuid() {
        when(mediaFileMapper.selectById(7L)).thenReturn(media(7L, 3L));
        OutboxEvent outboxEvent = new OutboxEvent();
        when(outboxEventFactory.analysisRequested(any()))
                .thenReturn(outboxEvent);

        AnalysisTaskCreatedView created = service.create(
                7L,
                3L,
                "总结关键结论"
        );

        assertThat(UUID.fromString(created.taskId())).isNotNull();
        assertThat(created.status()).isEqualTo(AnalysisTaskStatus.PENDING);
        ArgumentCaptor<AnalysisTask> taskCaptor =
                ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getMediaId()).isEqualTo(7L);
        assertThat(taskCaptor.getValue().getUserId()).isEqualTo(3L);
        assertThat(taskCaptor.getValue().getProgress()).isZero();
        verify(outboxEventMapper).insert(outboxEvent);
    }

    @Test
    void rejectsAMissingMedia() {
        when(mediaFileMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(99L, 3L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND)
                );
        verify(taskMapper, never()).insert(any(AnalysisTask.class));
    }

    @Test
    void rejectsAnotherUsersMedia() {
        when(mediaFileMapper.selectById(7L)).thenReturn(media(7L, 8L));

        assertThatThrownBy(() -> service.create(7L, 3L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN)
                );
    }

    @Test
    void letsTheOwnerRetryADeadTaskThroughANewOutboxEvent() {
        AnalysisTask task = task("task-1", 3L, AnalysisTaskStatus.DEAD);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);
        OutboxEvent event = new OutboxEvent();
        when(outboxEventFactory.manualRetryRequested(task))
                .thenReturn(event);

        var result = service.retry("task-1", 3L);

        assertThat(result.status()).isEqualTo(AnalysisTaskStatus.PENDING);
        verify(outboxEventMapper).insert(event);
    }

    @Test
    void letsTheOwnerCancelAQueuedTask() {
        when(taskMapper.selectOne(any())).thenReturn(
                task("task-1", 3L, AnalysisTaskStatus.QUEUED)
        );
        when(stateService.transition(any(), any(), any(), any()))
                .thenReturn(true);

        var result = service.cancel("task-1", 3L);

        assertThat(result.status()).isEqualTo(AnalysisTaskStatus.CANCELLED);
        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void rejectsCommandsFromAnotherUser() {
        when(taskMapper.selectOne(any())).thenReturn(
                task("task-1", 8L, AnalysisTaskStatus.DEAD)
        );

        assertThatThrownBy(() -> service.retry("task-1", 3L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN)
                );
    }

    private MediaFile media(Long id, Long userId) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(userId);
        return media;
    }

    private AnalysisTask task(
            String taskId,
            Long userId,
            AnalysisTaskStatus status
    ) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(taskId);
        task.setMediaId(7L);
        task.setUserId(userId);
        task.setStatus(status);
        task.setVersion(0L);
        return task;
    }
}
