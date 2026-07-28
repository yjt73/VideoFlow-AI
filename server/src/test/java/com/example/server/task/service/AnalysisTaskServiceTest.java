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
    private AnalysisTaskService service;

    @BeforeEach
    void setUp() {
        mediaFileMapper = mock(MediaFileMapper.class);
        taskMapper = mock(AnalysisTaskMapper.class);
        outboxEventMapper = mock(OutboxEventMapper.class);
        outboxEventFactory = mock(OutboxEventFactory.class);
        service = new AnalysisTaskService(
                mediaFileMapper,
                taskMapper,
                outboxEventMapper,
                outboxEventFactory
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

    private MediaFile media(Long id, Long userId) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(userId);
        return media;
    }
}
