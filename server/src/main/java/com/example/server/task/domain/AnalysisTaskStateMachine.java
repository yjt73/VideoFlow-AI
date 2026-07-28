package com.example.server.task.domain;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static com.example.server.task.domain.AnalysisTaskStatus.CANCELLED;
import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.FAILED;
import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;
import static com.example.server.task.domain.AnalysisTaskStatus.SUCCESS;

/** 集中定义分析任务允许发生的状态转换。 */
@Component
public class AnalysisTaskStateMachine {

    private final Map<AnalysisTaskStatus, EnumSet<AnalysisTaskStatus>> transitions =
            new EnumMap<>(AnalysisTaskStatus.class);

    public AnalysisTaskStateMachine() {
        transitions.put(PENDING, EnumSet.of(QUEUED, CANCELLED, DEAD));
        transitions.put(QUEUED, EnumSet.of(RUNNING, PENDING, CANCELLED, DEAD));
        transitions.put(
                RUNNING,
                EnumSet.of(SUCCESS, RETRYING, FAILED, DEAD, CANCELLED)
        );
        transitions.put(
                RETRYING,
                EnumSet.of(RUNNING, PENDING, FAILED, DEAD, CANCELLED)
        );
        transitions.put(FAILED, EnumSet.of(PENDING));
        transitions.put(DEAD, EnumSet.of(PENDING));
        transitions.put(SUCCESS, EnumSet.noneOf(AnalysisTaskStatus.class));
        transitions.put(CANCELLED, EnumSet.noneOf(AnalysisTaskStatus.class));
    }

    public boolean canTransition(AnalysisTaskStatus from, AnalysisTaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return transitions.getOrDefault(
                from,
                EnumSet.noneOf(AnalysisTaskStatus.class)
        ).contains(to);
    }
}
