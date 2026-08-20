package com.example.server.service;

import com.example.server.task.persistence.AnalysisTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

/** 将每次分析失败写入审计表，便于定位和后续补偿。 */
@Profile("legacy-persistence")
@Service
public class TaskFailureRecorder {

    private static final int MAX_STACK_LENGTH = 8000;

    private final AnalysisTaskMapper taskMapper;

    public TaskFailureRecorder(AnalysisTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public void record(
            String taskId,
            String failureType,
            int retryCount,
            String errorCode,
            Throwable failure,
            String rawMessage
    ) {
        taskMapper.recordFailure(
                taskId,
                failureType,
                retryCount,
                errorCode,
                safeMessage(failure),
                stackTrace(failure),
                rawMessage
        );
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }

    private String stackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        String stack = writer.toString();
        return stack.length() <= MAX_STACK_LENGTH
                ? stack
                : stack.substring(0, MAX_STACK_LENGTH);
    }
}
