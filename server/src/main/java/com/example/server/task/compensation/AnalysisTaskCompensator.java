package com.example.server.task.compensation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.task.domain.AnalysisTask;
import com.example.server.task.domain.AnalysisTaskStatus;
import com.example.server.task.outbox.OutboxEventFactory;
import com.example.server.task.persistence.AnalysisTaskMapper;
import com.example.server.task.persistence.OutboxEventMapper;
import com.example.server.task.service.AnalysisTaskStateService;
import com.example.server.task.service.TaskFailureRecorder;
import com.example.server.task.service.TaskProgressUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.example.server.task.domain.AnalysisTaskStatus.DEAD;
import static com.example.server.task.domain.AnalysisTaskStatus.PENDING;
import static com.example.server.task.domain.AnalysisTaskStatus.QUEUED;
import static com.example.server.task.domain.AnalysisTaskStatus.RETRYING;
import static com.example.server.task.domain.AnalysisTaskStatus.RUNNING;

/** 扫描长时间不推进的任务，并执行次数受限的可靠补偿。 */
@Component
public class AnalysisTaskCompensator {

    private static final Logger log =
            LoggerFactory.getLogger(AnalysisTaskCompensator.class);
    private static final Set<AnalysisTaskStatus> STALLED =
            Set.of(QUEUED, RUNNING, RETRYING);

    private final AnalysisTaskMapper taskMapper;
    private final AnalysisTaskStateService stateService;
    private final OutboxEventMapper outboxEventMapper;
    private final OutboxEventFactory outboxEventFactory;
    private final TaskFailureRecorder failureRecorder;
    private final int staleAfterMinutes;
    private final int maxAttempts;

    public AnalysisTaskCompensator(
            AnalysisTaskMapper taskMapper,
            AnalysisTaskStateService stateService,
            OutboxEventMapper outboxEventMapper,
            OutboxEventFactory outboxEventFactory,
            TaskFailureRecorder failureRecorder,
            @Value("${analysis.compensation.stale-after-minutes:30}")
            int staleAfterMinutes,
            @Value("${analysis.compensation.max-attempts:3}")
            int maxAttempts
    ) {
        this.taskMapper = taskMapper;
        this.stateService = stateService;
        this.outboxEventMapper = outboxEventMapper;
        this.outboxEventFactory = outboxEventFactory;
        this.failureRecorder = failureRecorder;
        this.staleAfterMinutes = staleAfterMinutes;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(
            fixedDelayString =
                    "${analysis.compensation.scan-delay-ms:60000}"
    )
    @Transactional
    public void compensateStalledTasks() {
        LocalDateTime cutoff =
                LocalDateTime.now().minusMinutes(staleAfterMinutes);
        List<AnalysisTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AnalysisTask>()
                        .in(AnalysisTask::getStatus, STALLED)
                        .lt(AnalysisTask::getUpdatedAt, cutoff)
                        .orderByAsc(AnalysisTask::getUpdatedAt)
                        .last("LIMIT 100")
        );
        for (AnalysisTask task : tasks) {
            compensate(task);
        }
    }

    private void compensate(AnalysisTask task) {
        int count = task.getCompensationCount() == null
                ? 0
                : task.getCompensationCount();
        if (count >= maxAttempts) {
            markDead(task);
            return;
        }

        if (task.getStatus() == RUNNING) {
            boolean movedToRetrying = stateService.transition(
                    task.getTaskId(),
                    Set.of(RUNNING),
                    RETRYING,
                    TaskProgressUpdate.failure(
                            task.getProgress() == null
                                    ? 10
                                    : task.getProgress(),
                            "COMPENSATING",
                            "TASK_STALLED",
                            "运行超时，准备补偿"
                    )
            );
            if (!movedToRetrying) {
                return;
            }
        }
        boolean reset = stateService.transition(
                task.getTaskId(),
                Set.of(QUEUED, RETRYING),
                PENDING,
                TaskProgressUpdate.reset(0, "COMPENSATION_QUEUED")
        );
        if (!reset
                || taskMapper.incrementCompensation(
                        task.getTaskId(),
                        count
                ) != 1) {
            return;
        }

        int generation = count + 1;
        outboxEventMapper.insert(
                outboxEventFactory.compensationRequested(task, generation)
        );
        log.warn(
                "event=analysis_task_compensated taskId={} generation={}",
                task.getTaskId(),
                generation
        );
    }

    private void markDead(AnalysisTask task) {
        boolean marked = stateService.transition(
                task.getTaskId(),
                STALLED,
                DEAD,
                TaskProgressUpdate.failure(
                        task.getProgress() == null ? 10 : task.getProgress(),
                        "COMPENSATION_EXHAUSTED",
                        "COMPENSATION_EXHAUSTED",
                        "任务多次补偿后仍未推进"
                )
        );
        if (!marked) {
            return;
        }
        int retryCount =
                task.getRetryCount() == null ? 0 : task.getRetryCount();
        IllegalStateException failure = new IllegalStateException(
                "Task compensation attempts exhausted"
        );
        failureRecorder.record(
                task.getTaskId(),
                "COMPENSATION",
                retryCount,
                "COMPENSATION_EXHAUSTED",
                failure,
                null
        );
        log.error(
                "event=analysis_compensation_exhausted taskId={} "
                        + "compensationCount={}",
                task.getTaskId(),
                task.getCompensationCount()
        );
    }
}
