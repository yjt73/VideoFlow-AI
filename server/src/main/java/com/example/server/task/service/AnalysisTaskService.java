package com.example.server.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.task.api.AnalysisTaskCreatedView;
import com.example.server.task.api.AnalysisTaskView;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.domain.AnalysisTaskType;
import com.example.server.task.persistence.AnalysisTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 创建和查询当前用户拥有的视频分析任务。 */
@Service
public class AnalysisTaskService {

    private static final String DEFAULT_GOAL = "理解视频核心内容并生成结构化分析报告";

    private final MediaFileMapper mediaFileMapper;
    private final AnalysisTaskMapper taskMapper;

    public AnalysisTaskService(
            MediaFileMapper mediaFileMapper,
            AnalysisTaskMapper taskMapper
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.taskMapper = taskMapper;
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

        return new AnalysisTaskCreatedView(
                task.getTaskId(),
                task.getStatus(),
                task.getCreatedAt()
        );
    }

    public AnalysisTaskView findOwned(String taskId, Long userId) {
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
        return AnalysisTaskView.from(task);
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
