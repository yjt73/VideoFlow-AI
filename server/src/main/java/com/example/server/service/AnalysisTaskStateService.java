package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStateMachine;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.persistence.AnalysisTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.Set;

/** 通过数据库条件更新安全推进分析任务状态。 */
@Profile("legacy-persistence")
@Service
public class AnalysisTaskStateService {

    private final AnalysisTaskMapper taskMapper;
    private final AnalysisTaskStateMachine stateMachine;

    public AnalysisTaskStateService(
            AnalysisTaskMapper taskMapper,
            AnalysisTaskStateMachine stateMachine
    ) {
        this.taskMapper = taskMapper;
        this.stateMachine = stateMachine;
    }

    public boolean transition(
            String taskId,
            Set<AnalysisTaskStatus> expected,
            AnalysisTaskStatus target,
            TaskProgressUpdate update
    ) {
        AnalysisTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getTaskId, taskId)
        );
        if (task == null
                || expected == null
                || !expected.contains(task.getStatus())
                || !stateMachine.canTransition(task.getStatus(), target)) {
            return false;
        }

        long version = task.getVersion() == null ? 0L : task.getVersion();
        return taskMapper.transition(
                taskId,
                version,
                task.getStatus().name(),
                target.name(),
                update == null ? TaskProgressUpdate.none() : update
        ) == 1;
    }
}
