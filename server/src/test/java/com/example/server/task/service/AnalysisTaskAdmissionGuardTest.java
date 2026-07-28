package com.example.server.task.service;

import com.example.server.api.BusinessException;
import com.example.server.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisTaskAdmissionGuardTest {

    @Test
    void rejectsNewTasksWhenTheGlobalAiBudgetIsExhausted() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter("limit:ai:global"))
                .thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(false);
        AnalysisTaskAdmissionGuard guard =
                new AnalysisTaskAdmissionGuard(redissonClient);

        assertThatThrownBy(guard::check)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RATE_LIMITED)
                );
    }
}
