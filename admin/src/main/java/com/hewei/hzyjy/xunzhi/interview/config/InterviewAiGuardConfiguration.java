package com.hewei.hzyjy.xunzhi.interview.config;

import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * AI guard configuration for interview workflow.
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.ai-guard")
public class InterviewAiGuardConfiguration {

    private Boolean enable = true;

    private Integer circuitSlidingWindowSize = 50;

    private Float circuitFailureRateThreshold = 50F;

    private Integer circuitPermittedCallsInHalfOpenState = 10;

    private Long circuitOpenStateWaitMillis = 30000L;

    private Integer executorThreads = 32;

    private Map<String, StagePolicy> stages = new HashMap<>();

    public StagePolicy resolveStagePolicy(String stage) {
        StagePolicy configured = stages == null ? null : stages.get(stage);
        if (configured != null) {
            return withDefaults(configured, defaultByStage(stage));
        }
        return defaultByStage(stage);
    }

    private StagePolicy withDefaults(StagePolicy configured, StagePolicy defaults) {
        StagePolicy merged = new StagePolicy();
        merged.setTimeoutMillis(positiveOrDefault(configured.getTimeoutMillis(), defaults.getTimeoutMillis()));
        merged.setMaxConcurrentCalls(positiveOrDefault(configured.getMaxConcurrentCalls(), defaults.getMaxConcurrentCalls()));
        merged.setRetryCount(nonNegativeOrDefault(configured.getRetryCount(), defaults.getRetryCount()));
        merged.setRetryWaitMillis(nonNegativeOrDefault(configured.getRetryWaitMillis(), defaults.getRetryWaitMillis()));
        return merged;
    }

    private StagePolicy defaultByStage(String stage) {
        if (InterviewAiGuardStage.INTERVIEW_EVALUATION.equals(stage)) {
            return new StagePolicy(20000L, 30, 1, 100L);
        }
        if (InterviewAiGuardStage.INTERVIEW_FOLLOWUP.equals(stage)) {
            return new StagePolicy(20000L, 20, 1, 100L);
        }
        if (InterviewAiGuardStage.INTERVIEW_EXTRACTION.equals(stage)) {
            return new StagePolicy(60000L, 8, 0, 0L);
        }
        if (InterviewAiGuardStage.INTERVIEW_DEMEANOR.equals(stage)) {
            return new StagePolicy(20000L, 6, 0, 0L);
        }
        return new StagePolicy(20000L, 20, 0, 0L);
    }

    private Long positiveOrDefault(Long value, Long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private Integer positiveOrDefault(Integer value, Integer defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private Long nonNegativeOrDefault(Long value, Long defaultValue) {
        return value != null && value >= 0 ? value : defaultValue;
    }

    private Integer nonNegativeOrDefault(Integer value, Integer defaultValue) {
        return value != null && value >= 0 ? value : defaultValue;
    }

    @Data
    public static class StagePolicy {
        private Long timeoutMillis;
        private Integer maxConcurrentCalls;
        private Integer retryCount;
        private Long retryWaitMillis;

        public StagePolicy() {
        }

        public StagePolicy(Long timeoutMillis, Integer maxConcurrentCalls, Integer retryCount, Long retryWaitMillis) {
            this.timeoutMillis = timeoutMillis;
            this.maxConcurrentCalls = maxConcurrentCalls;
            this.retryCount = retryCount;
            this.retryWaitMillis = retryWaitMillis;
        }
    }
}
