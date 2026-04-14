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
        String limiterKey = resolveKeyPrefix() + ":" + bucketName + ":" + key;
        RRateLimiter limiter = redissonClient.getRateLimiter(limiterKey);
        ensureLimiterConfigured(limiter, effectivePolicy);
        return limiter.tryAcquire(effectivePolicy.requestedTokens());
    }

    private void ensureLimiterConfigured(RRateLimiter limiter, RequestRateLimitPolicy policy) {
        Boolean exists = limiter.isExists();
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        limiter.trySetRate(
                RateType.OVERALL,
                policy.maxAccessCount(),
                policy.timeWindowSeconds(),
                RateIntervalUnit.SECONDS
        );
        limiter.expire(resolveExpirationSeconds(policy.timeWindowSeconds()), TimeUnit.SECONDS);
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
