package com.hewei.hzyjy.xunzhi.interview.application.pipeline;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewTurnRepairConfiguration;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Compensates failed turn persistence asynchronously.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewTurnRepairService {

    private static final String TURN_REPAIR_QUEUE_KEY = "interview:turn:repair:queue";

    private final StringRedisTemplate stringRedisTemplate;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewTurnRepairConfiguration configuration;
    private final MeterRegistry meterRegistry;

    public void enqueue(String sessionId, InterviewTurnLog turnLog, String reason) {
        if (StrUtil.isBlank(sessionId) || turnLog == null) {
            return;
        }
        try {
            TurnRepairTask task = new TurnRepairTask();
            task.setSessionId(sessionId);
            task.setRetryCount(0);
            task.setReason(StrUtil.blankToDefault(reason, "unknown"));
            task.setTurn(turnLog);
            stringRedisTemplate.opsForList().rightPush(TURN_REPAIR_QUEUE_KEY, JSON.toJSONString(task));
            stringRedisTemplate.expire(TURN_REPAIR_QUEUE_KEY, 24, TimeUnit.HOURS);
            meterRegistry.counter("turn_repair_enqueue_total").increment();
        } catch (Exception ex) {
            meterRegistry.counter("turn_repair_enqueue_fail_total").increment();
            log.warn("Failed to enqueue turn repair task, sessionId={}, requestId={}", sessionId, turnLog.getRequestId(), ex);
        }
    }

    @Scheduled(fixedDelayString = "${xunzhi-agent.interview.turn-repair.fixed-delay-millis:3000}")
    public void repairPendingTurns() {
        if (!Boolean.TRUE.equals(configuration.getEnable())) {
            return;
        }
        int batchSize = resolveBatchSize();
        for (int i = 0; i < batchSize; i++) {
            String payload = stringRedisTemplate.opsForList().leftPop(TURN_REPAIR_QUEUE_KEY);
            if (StrUtil.isBlank(payload)) {
                return;
            }
            TurnRepairTask task = parseTask(payload);
            if (task == null || StrUtil.isBlank(task.getSessionId()) || task.getTurn() == null) {
                meterRegistry.counter("turn_repair_parse_fail_total").increment();
                continue;
            }
            boolean repaired = interviewQuestionCacheService.appendInterviewTurnIfAbsent(task.getSessionId(), task.getTurn());
            if (repaired) {
                meterRegistry.counter("turn_repair_success_total").increment();
                continue;
            }
            int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
            if (nextRetry > resolveMaxRetries()) {
                meterRegistry.counter("turn_repair_drop_total").increment();
                log.warn("Drop turn repair task after max retries, sessionId={}, requestId={}",
                        task.getSessionId(),
                        task.getTurn() == null ? null : task.getTurn().getRequestId());
                continue;
            }
            task.setRetryCount(nextRetry);
            stringRedisTemplate.opsForList().rightPush(TURN_REPAIR_QUEUE_KEY, JSON.toJSONString(task));
            meterRegistry.counter("turn_repair_retry_total").increment();
        }
    }

    private TurnRepairTask parseTask(String payload) {
        try {
            return JSON.parseObject(payload, TurnRepairTask.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private int resolveBatchSize() {
        Integer configured = configuration.getBatchSize();
        return configured != null && configured > 0 ? configured : 50;
    }

    private int resolveMaxRetries() {
        Integer configured = configuration.getMaxRetries();
        return configured != null && configured > 0 ? configured : 6;
    }

    @Data
    public static class TurnRepairTask {
        private String sessionId;
        private InterviewTurnLog turn;
        private Integer retryCount;
        private String reason;
    }
}
