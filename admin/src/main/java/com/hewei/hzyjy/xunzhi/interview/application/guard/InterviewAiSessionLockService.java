package com.hewei.hzyjy.xunzhi.interview.application.guard;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Session-level lock for heavy AI operations.
 */
@Service
@RequiredArgsConstructor
public class InterviewAiSessionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:ai:heavy:lock:";

    private final RedissonClient redissonClient;
    private final InterviewAiSingleFlightConfiguration configuration;

    public RLock acquire(String sessionId, String stage) throws InterruptedException {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(stage)) {
            return null;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + stage + ":" + sessionId);
        boolean acquired = lock.tryLock(
                resolveWaitMillis(),
                resolveExpireSeconds(),
                TimeUnit.SECONDS
        );
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private long resolveExpireSeconds() {
        Long configured = configuration.getHeavyLockExpireSeconds();
        return configured != null && configured > 0 ? configured : 45L;
    }

    private long resolveWaitMillis() {
        Long configured = configuration.getHeavyLockWaitMillis();
        return configured != null && configured >= 0 ? configured : 0L;
    }
}
