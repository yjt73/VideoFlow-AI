package com.example.server.service.mode;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.ModeClassification;
import com.example.server.dto.RouteDecision;
import com.example.server.utils.DeepSeekUtils;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);
    private static final int USER_ROUTES_PER_MINUTE = 10;
    private static final int GLOBAL_ROUTES_PER_MINUTE = 60;

    private final DeepSeekUtils deepSeekUtils;
    private final RedissonClient redissonClient;

    public ModeRouter(DeepSeekUtils deepSeekUtils, RedissonClient redissonClient) {
        this.deepSeekUtils = deepSeekUtils;
        this.redissonClient = redissonClient;
    }

    public RouteDecision route(String goal) {
        if (goal == null || goal.isBlank()) {
            return new RouteDecision(AnalysisMode.GENERAL, "未提供分析目标，已按通用模式分析");
        }
        try {
            ModeClassification classification = deepSeekUtils.classifyMode(goal.trim());
            AnalysisMode mode = AnalysisMode.fromNullable(
                    classification == null ? null : classification.mode());
            String reason = classification == null ? "" : classification.reason();
            return new RouteDecision(mode, reason);
        } catch (RuntimeException exception) {
            log.warn("自动意图路由失败，回退 GENERAL。goalLength={}", goal.length(), exception);
            return new RouteDecision(AnalysisMode.GENERAL, "意图识别暂不可用，已按通用模式分析");
        }
    }

    public RouteDecision route(String goal, Long userId) {
        if (!tryAcquireQuota(userId)) {
            return new RouteDecision(AnalysisMode.GENERAL, "自动路由当前繁忙，已按通用模式分析");
        }
        return route(goal);
    }

    private boolean tryAcquireQuota(Long userId) {
        if (userId == null) return false;
        try {
            RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:route:user:" + userId);
            userLimiter.trySetRate(RateType.OVERALL, USER_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
            if (!userLimiter.tryAcquire()) return false;
            RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:route:global");
            globalLimiter.trySetRate(RateType.OVERALL, GLOBAL_ROUTES_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
            return globalLimiter.tryAcquire();
        } catch (RuntimeException exception) {
            log.warn("自动路由限流器不可用，回退 GENERAL。userId={}", userId, exception);
            return false;
        }
    }
}
