package com.example.server.task.consumer;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 将下游抖动与不可恢复的业务错误分开处理。 */
@Component
public class TaskExceptionClassifier {

    public TaskFailureType classify(Throwable failure) {
        Throwable cause = rootCause(failure);
        if (cause instanceof IOException) {
            return TaskFailureType.RETRYABLE;
        }
        if (cause instanceof BusinessException businessException) {
            ErrorCode code = businessException.getErrorCode();
            return switch (code) {
                case RATE_LIMITED, DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR ->
                        TaskFailureType.RETRYABLE;
                default -> TaskFailureType.PERMANENT;
            };
        }
        return TaskFailureType.RETRYABLE;
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
