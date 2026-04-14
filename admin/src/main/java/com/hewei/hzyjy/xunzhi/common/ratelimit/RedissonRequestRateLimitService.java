package com.hewei.hzyjy.xunzhi.common.ratelimit;

import com.hewei.hzyjy.xunzhi.common.config.user.UserFlowRiskControlConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Distributed request rate limiter backed by Redisson.
 */
@Service
@RequiredArgsConstructor
public class RedissonRequestRateLimitService implements RequestRateLimitService {

    private final RedissonClient redissonClient;
    private final UserFlowRiskControlConfiguration configuration;

    @Override
    public boolean tryAcquire(String key, RequestRateLimitPolicy policy) {
        RequestRateLimitPolicy effectivePolicy = policy != null ? policy : resolveDefaultPolicy();
        String bucketName = (effectivePolicy.bucketName() == null || effectivePolicy.bucketName().isBlank())
                ? "default"
                : effectivePolicy.bucketName().trim();
        RRateLimiter limiter = redissonClient.getRateLimiter(resolveKeyPrefix() + ":" + bucketName + ":" + key);
        limiter.trySetRate(
                RateType.OVERALL,
                effectivePolicy.maxAccessCount(),
                effectivePolicy.timeWindowSeconds(),
                RateIntervalUnit.SECONDS
        );
        limiter.expire(resolveExpirationSeconds(effectivePolicy.timeWindowSeconds()), TimeUnit.SECONDS);
        return limiter.tryAcquire(effectivePolicy.requestedTokens());
    }

    private long resolveExpirationSeconds(long timeWindowSeconds) {
        return Math.max(timeWindowSeconds * 2, 60L);
    }

    private RequestRateLimitPolicy resolveDefaultPolicy() {
        long maxAccessCount = configuration.getMaxAccessCount() != null && configuration.getMaxAccessCount() > 0
                ? configuration.getMaxAccessCount()
                : 20L;
        long timeWindowSeconds = configuration.getTimeWindowSeconds() != null && configuration.getTimeWindowSeconds() > 0
                ? configuration.getTimeWindowSeconds()
                : 1L;
        long requestedTokens = configuration.getRequestedTokens() != null && configuration.getRequestedTokens() > 0
                ? configuration.getRequestedTokens()
                : 1L;
        return new RequestRateLimitPolicy("default", maxAccessCount, timeWindowSeconds, requestedTokens);
    }

    private String resolveKeyPrefix() {
        String keyPrefix = configuration.getKeyPrefix();
        return keyPrefix == null || keyPrefix.isBlank() ? "xunzhi-agent:rate-limit" : keyPrefix;
    }
}
