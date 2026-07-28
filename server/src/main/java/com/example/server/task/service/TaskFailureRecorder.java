package com.example.server.task.service;

import com.example.server.task.domain.AnalysisTaskFailure;
import com.example.server.task.persistence.AnalysisTaskFailureMapper;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

/** 将每次分析失败写入审计表，便于定位和后续补偿。 */
@Service
public class TaskFailureRecorder {

    private static final int MAX_STACK_LENGTH = 8000;

    private final AnalysisTaskFailureMapper failureMapper;

    public TaskFailureRecorder(AnalysisTaskFailureMapper failureMapper) {
        this.failureMapper = failureMapper;
    }

    public void record(
            String taskId,
            String failureType,
            int retryCount,
            String errorCode,
            Throwable failure,
            String rawMessage
    ) {
        AnalysisTaskFailure record = new AnalysisTaskFailure();
        record.setTaskId(taskId);
        record.setFailureType(failureType);
        record.setRetryCount(retryCount);
        record.setErrorCode(errorCode);
        record.setErrorMessage(safeMessage(failure));
        record.setStackTrace(stackTrace(failure));
        record.setRawMessage(rawMessage);
        record.setCreatedAt(LocalDateTime.now());
        failureMapper.insert(record);
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
