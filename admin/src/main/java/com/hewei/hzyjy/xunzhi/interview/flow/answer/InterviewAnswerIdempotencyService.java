package com.hewei.hzyjy.xunzhi.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAnswerGuardConfiguration;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Idempotency state manager for interview answer requests.
 * <p>
 * 已提交结果由原子提交 Lua 脚本写入 committedRecords Hash（interview:answer:committed:session:{sid}），
 * 本服务负责：① committedRecords 预检回放（指纹一致才回放，不符明确报错）；
 * ② in-flight 围栏（processing key，SET NX 原子抢占）。
 */
@Service
@RequiredArgsConstructor
public class InterviewAnswerIdempotencyService {

    private static final String PROCESSING_KEY_PREFIX = "interview:answer:idempotency:processing:{";

    private final StringRedisTemplate stringRedisTemplate;
    private final InterviewAnswerGuardConfiguration configuration;

    public TryStartResult tryStart(String sessionId, String requestId, String questionNumber, String answerDigest) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return TryStartResult.newRequest();
        }
        // 1) committedRecords 预检：已提交且指纹一致 → 回放；指纹不符 → 明确报错（fail closed，不覆盖）。
        Object record = stringRedisTemplate.opsForHash().get(
                InterviewCacheKeys.committedAnswer(sessionId), requestId);
        if (record != null) {
            try {
                JSONObject committed = JSON.parseObject(record.toString());
                if (committed != null
                        && Objects.equals(committed.getString("questionNumber"), questionNumber)
                        && Objects.equals(committed.getString("answerDigest"), answerDigest)) {
                    return TryStartResult.succeeded(record.toString());
                }
                return TryStartResult.fingerprintMismatch();
            } catch (Exception ex) {
                // 记录损坏：按指纹不符处理（fail closed）。
                return TryStartResult.fingerprintMismatch();
            }
        }
        // 2) in-flight 围栏：同 requestId 同时只允许一个请求进入管道。
        Boolean started = stringRedisTemplate.opsForValue().setIfAbsent(
                processingKey(sessionId, requestId),
                "1",
                resolveProcessingExpireSeconds(),
                TimeUnit.SECONDS
        );
        if (Boolean.TRUE.equals(started)) {
            return TryStartResult.newRequest();
        }
        return TryStartResult.processing();
    }

    public void clearProcessing(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return;
        }
        stringRedisTemplate.delete(processingKey(sessionId, requestId));
    }

    private String processingKey(String sessionId, String requestId) {
        return PROCESSING_KEY_PREFIX + sessionId + "}:" + requestId;
    }

    private long resolveProcessingExpireSeconds() {
        Long configured = configuration.getProcessingExpireSeconds();
        long base = configured == null || configured <= 0 ? 120L : configured;
        Long longTail = configuration.getProcessingLongTailExpireSeconds();
        long guard = longTail == null || longTail <= 0 ? base : longTail;
        return Math.max(base, guard);
    }

    @Getter
    public static final class TryStartResult {
        private final TryStartStatus status;
        private final String replayRecordJson;

        private TryStartResult(TryStartStatus status, String replayRecordJson) {
            this.status = status;
            this.replayRecordJson = replayRecordJson;
        }

        public static TryStartResult newRequest() {
            return new TryStartResult(TryStartStatus.NEW, null);
        }

        public static TryStartResult processing() {
            return new TryStartResult(TryStartStatus.PROCESSING, null);
        }

        public static TryStartResult succeeded(String replayRecordJson) {
            return new TryStartResult(TryStartStatus.SUCCEEDED, replayRecordJson);
        }

        public static TryStartResult fingerprintMismatch() {
            return new TryStartResult(TryStartStatus.FINGERPRINT_MISMATCH, null);
        }
    }

    public enum TryStartStatus {
        NEW,
        PROCESSING,
        SUCCEEDED,
        FINGERPRINT_MISMATCH
    }
}
