package com.hewei.hzyjy.xunzhi.interview.application.pipeline;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAnswerGuardConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Distributed lock guard for one interview question per session.
 */
@Service
@RequiredArgsConstructor
public class InterviewQuestionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:answer:lock:";

    private final RedissonClient redissonClient;
    private final InterviewAnswerGuardConfiguration configuration;

    public RLock acquire(String sessionId, String questionNumber) throws InterruptedException {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber)) {
            return null;
        }
        RLock lock = redissonClient.getLock(lockKey(sessionId, questionNumber));
        boolean acquired;
        if (useWatchdogLock()) {
            acquired = lock.tryLock(resolveWaitMillis(), -1L, TimeUnit.MILLISECONDS);
        } else {
            acquired = lock.tryLock(resolveWaitMillis(), resolveExpireMillis(), TimeUnit.MILLISECONDS);
        }
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private String lockKey(String sessionId, String questionNumber) {
        return LOCK_KEY_PREFIX + sessionId + ":" + questionNumber;
    }

    private long resolveExpireMillis() {
        Long configured = configuration.getLockExpireSeconds();
        long seconds = configured == null || configured <= 0 ? 120L : configured;
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private long resolveWaitMillis() {
        Long configured = configuration.getLockWaitMillis();
        return configured == null || configured < 0 ? 0L : configured;
    }

    private boolean useWatchdogLock() {
        return Boolean.TRUE.equals(configuration.getLockWatchdogEnabled())
                && (configuration.getLockExpireSeconds() == null || configuration.getLockExpireSeconds() <= 0);
    }
}
