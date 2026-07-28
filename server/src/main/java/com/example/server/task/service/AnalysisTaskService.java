package com.example.server.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.task.api.AnalysisTaskCreatedView;
import com.example.server.task.api.AnalysisTaskView;
import com.example.server.task.api.TaskCommandView;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.AnalysisTaskType;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.task.outbox.OutboxEventFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.CANCELLED;
import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.FAILED;
import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;

/** 创建和查询当前用户拥有的视频分析任务。 */
@Service
public class AnalysisTaskService {

    private static final String DEFAULT_GOAL = "理解视频核心内容并生成结构化分析报告";

    private final MediaFileMapper mediaFileMapper;
    private final AnalysisTaskMapper taskMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final OutboxEventFactory outboxEventFactory;
    private final AnalysisTaskStateService stateService;

    public AnalysisTaskService(
            MediaFileMapper mediaFileMapper,
            AnalysisTaskMapper taskMapper,
            OutboxEventMapper outboxEventMapper,
            OutboxEventFactory outboxEventFactory,
            AnalysisTaskStateService stateService
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.taskMapper = taskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.outboxEventFactory = outboxEventFactory;
        this.stateService = stateService;
    }

    @Transactional
    public AnalysisTaskCreatedView create(
            Long mediaId,
            Long userId,
            String userGoal
    ) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (!Objects.equals(media.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权分析他人的媒体");
        }

        String normalizedGoal = normalizeGoal(userGoal);
        LocalDateTime now = LocalDateTime.now();
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setMediaId(mediaId);
        task.setUserId(userId);
        task.setTaskType(AnalysisTaskType.VIDEO_ANALYSIS);
        task.setStatus(AnalysisTaskStatus.PENDING);
        task.setRetryCount(0);
        task.setCompensationCount(0);
        task.setProgress(0);
        task.setCurrentStage("CREATED");
        task.setUserGoal(normalizedGoal);
        task.setVersion(0L);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        outboxEventMapper.insert(outboxEventFactory.analysisRequested(task));

        return new AnalysisTaskCreatedView(
                task.getTaskId(),
                task.getStatus(),
                task.getCreatedAt()
        );
    }

    public AnalysisTaskView findOwned(String taskId, Long userId) {
        return AnalysisTaskView.from(findOwnedEntity(taskId, userId));
    }

    @Transactional
    public TaskCommandView retry(String taskId, Long userId) {
        AnalysisTask task = findOwnedEntity(taskId, userId);
        boolean reset = stateService.transition(
                taskId,
                Set.of(FAILED, DEAD),
                PENDING,
                TaskProgressUpdate.reset(0, "MANUAL_RETRY_QUEUED")
        );
        if (!reset) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "只有失败或死信任务可以重试"
            );
        }
        outboxEventMapper.insert(
                outboxEventFactory.manualRetryRequested(task)
        );
        return new TaskCommandView(taskId, PENDING);
    }

    @Transactional
    public TaskCommandView cancel(String taskId, Long userId) {
        findOwnedEntity(taskId, userId);
        boolean cancelled = stateService.transition(
                taskId,
                Set.of(PENDING, QUEUED, RUNNING, RETRYING),
                CANCELLED,
                TaskProgressUpdate.progress(0, "CANCELLED_BY_USER")
        );
        if (!cancelled) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "当前任务状态不能取消"
            );
        }
        return new TaskCommandView(taskId, CANCELLED);
    }

    private AnalysisTask findOwnedEntity(String taskId, Long userId) {
        AnalysisTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getTaskId, taskId)
        );
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分析任务不存在");
        }
        if (!Objects.equals(task.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人的分析任务");
        }
        return task;
    }

    private String normalizeGoal(String userGoal) {
        if (userGoal == null || userGoal.isBlank()) {
            return DEFAULT_GOAL;
        }
        String trimmed = userGoal.trim();
        if (trimmed.length() > 500) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "分析目标不能超过 500 字"
            );
        }
        return trimmed;
    }
}
