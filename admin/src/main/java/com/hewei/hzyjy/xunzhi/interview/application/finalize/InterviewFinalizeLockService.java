package com.hewei.hzyjy.xunzhi.interview.application.finalize;

import cn.hutool.core.util.StrUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Session-level lock used by interview finalize flow to avoid duplicate finish/save races.
 */
@Service
public class InterviewFinalizeLockService {

    private static final String LOCK_KEY_PREFIX = "interview:finalize:lock:";
    private static final long LOCK_WAIT_MILLIS = 0L;
    private static final long LOCK_LEASE_SECONDS = 120L;

    private final RedissonClient redissonClient;

    public InterviewFinalizeLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public RLock acquire(String sessionId) throws InterruptedException {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + sessionId);
        boolean acquired = lock.tryLock(LOCK_WAIT_MILLIS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
