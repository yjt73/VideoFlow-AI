package com.example.server.task.service;

import com.example.server.api.BusinessException;
import com.example.server.api.DependencyUnavailableException;
import com.example.server.api.ErrorCode;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/** 在创建分析任务前执行全局 AI 预算限流。 */
@Component
public class AnalysisTaskAdmissionGuard {

    private final RedissonClient redissonClient;

    public AnalysisTaskAdmissionGuard(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void check() {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(
                    "limit:ai:global"
            );
            limiter.trySetRate(
                    RateType.OVERALL,
                    10,
                    1,
                    RateIntervalUnit.MINUTES
            );
            if (!limiter.tryAcquire(1)) {
                throw new BusinessException(
                        ErrorCode.RATE_LIMITED,
                        "系统繁忙，请稍后再试"
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException(
                    "分析限流服务暂时不可用",
                    exception
            );
        }
    }
}
