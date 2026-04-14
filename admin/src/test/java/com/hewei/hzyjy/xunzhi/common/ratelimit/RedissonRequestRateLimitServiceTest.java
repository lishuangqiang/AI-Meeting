package com.hewei.hzyjy.xunzhi.common.ratelimit;

import com.hewei.hzyjy.xunzhi.common.config.user.UserFlowRiskControlConfiguration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedissonRequestRateLimitServiceTest {

    @Test
    void shouldConfigureLimiterAndAcquirePermit() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        UserFlowRiskControlConfiguration configuration = buildConfiguration();
        RedissonRequestRateLimitService service = new RedissonRequestRateLimitService(redissonClient, configuration);

        when(redissonClient.getRateLimiter("xunzhi-agent:rate-limit:default:user:alice")).thenReturn(rateLimiter);
        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.tryAcquire(1L)).thenReturn(true);

        assertTrue(service.tryAcquire("user:alice", null));
        verify(rateLimiter).trySetRate(RateType.OVERALL, 20L, 1L, RateIntervalUnit.SECONDS);
        verify(rateLimiter).expire(60L, TimeUnit.SECONDS);
        verify(rateLimiter).tryAcquire(1L);
    }

    @Test
    void shouldReturnFalseWhenLimiterRejectsRequest() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        UserFlowRiskControlConfiguration configuration = buildConfiguration();
        RedissonRequestRateLimitService service = new RedissonRequestRateLimitService(redissonClient, configuration);

        when(redissonClient.getRateLimiter("xunzhi-agent:rate-limit:default:ip:127.0.0.1")).thenReturn(rateLimiter);
        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.tryAcquire(1L)).thenReturn(false);

        assertFalse(service.tryAcquire("ip:127.0.0.1", null));
    }

    @Test
    void shouldNotReconfigureLimiterWhenPolicyUnchanged() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        UserFlowRiskControlConfiguration configuration = buildConfiguration();
        RedissonRequestRateLimitService service = new RedissonRequestRateLimitService(redissonClient, configuration);

        when(redissonClient.getRateLimiter("xunzhi-agent:rate-limit:default:user:bob")).thenReturn(rateLimiter);
        when(rateLimiter.isExists()).thenReturn(false, true);
        when(rateLimiter.tryAcquire(1L)).thenReturn(true);

        assertTrue(service.tryAcquire("user:bob", null));
        assertTrue(service.tryAcquire("user:bob", null));

        verify(rateLimiter, times(1)).trySetRate(RateType.OVERALL, 20L, 1L, RateIntervalUnit.SECONDS);
        verify(rateLimiter, times(1)).expire(60L, TimeUnit.SECONDS);
        verify(rateLimiter, times(2)).tryAcquire(1L);
    }

    private UserFlowRiskControlConfiguration buildConfiguration() {
        UserFlowRiskControlConfiguration configuration = new UserFlowRiskControlConfiguration();
        configuration.setKeyPrefix("xunzhi-agent:rate-limit");
        configuration.setMaxAccessCount(20L);
        configuration.setTimeWindowSeconds(1L);
        configuration.setRequestedTokens(1L);
        return configuration;
    }
}
