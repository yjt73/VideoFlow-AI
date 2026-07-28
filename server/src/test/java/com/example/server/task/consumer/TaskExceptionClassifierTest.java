package com.example.server.task.consumer;

import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExceptionClassifierTest {

    private final TaskExceptionClassifier classifier =
            new TaskExceptionClassifier();

    @Test
    void treatsNetworkRateLimitAndDependencyFailuresAsRetryable() {
        assertThat(classifier.classify(new SocketTimeoutException("timeout")))
                .isEqualTo(TaskFailureType.RETRYABLE);
        assertThat(classifier.classify(
                new BusinessException(ErrorCode.RATE_LIMITED, "limited")
        )).isEqualTo(TaskFailureType.RETRYABLE);
        assertThat(classifier.classify(
                new DependencyUnavailableException("down")
        )).isEqualTo(TaskFailureType.RETRYABLE);
    }

    @Test
    void treatsInvalidRequestsAndMissingResourcesAsPermanent() {
        assertThat(classifier.classify(
                new BusinessException(ErrorCode.BAD_REQUEST, "bad request")
        )).isEqualTo(TaskFailureType.PERMANENT);
        assertThat(classifier.classify(
                new BusinessException(ErrorCode.NOT_FOUND, "not found")
        )).isEqualTo(TaskFailureType.PERMANENT);
    }
}
