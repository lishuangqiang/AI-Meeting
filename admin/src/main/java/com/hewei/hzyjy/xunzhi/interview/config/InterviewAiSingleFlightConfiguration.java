package com.hewei.hzyjy.xunzhi.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 面试 AI single-flight 的总配置类，负责承载本地复用、分布式复用、结果缓存、
 * 心跳续租以及重锁等待等运行参数，并支持按不同业务阶段覆盖差异化策略。
 *
 * @author 程序员牛肉
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.ai-singleflight")
public class InterviewAiSingleFlightConfiguration {

    private Boolean enable = true;

    private String mode = "local";

    private Boolean distributedEnabled = false;

    private Long ttlMillis = 65000L;

    private Long waitTimeoutMillis = 65000L;

    private Long streamBlockTimeoutMillis = 3000L;

    private Long pollFallbackIntervalMillis = 2000L;

    private Long followerMaxWaitMillis = 20000L;

    private Integer l1CacheMaxSize = 1000;

    private Integer cleanupThreshold = 256;

    private Long heavyLockExpireSeconds = 90L;

    private Long heavyLockWaitMillis = 0L;

    private Map<String, StageFlightPolicy> stagePolicies = new LinkedHashMap<>();

    public StageFlightPolicy resolveStagePolicy(String stage) {
        StageFlightPolicy fallback = new StageFlightPolicy();
        if (stage == null || stage.isBlank()) {
            return fallback;
        }
        StageFlightPolicy policy = stagePolicies.get(stage);
        if (policy == null) {
            return fallback;
        }
        return fallback.merge(policy);
    }

    public boolean isDistributedMode() {
        return "distributed".equalsIgnoreCase(normalizedMode()) || "hybrid".equalsIgnoreCase(normalizedMode());
    }

    public boolean isHybridMode() {
        return "hybrid".equalsIgnoreCase(normalizedMode());
    }

    public String normalizedMode() {
        return mode == null ? "local" : mode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 按业务 stage 定义 single-flight 细粒度策略的配置对象，
     * 用于区分不同阶段的 heartbeat、TTL、压缩和 L1 缓存策略。
     *
     * @author 程序员牛肉
     */
    @Data
    public static class StageFlightPolicy {

        private Long heartbeatIntervalMillis = 3000L;

        private Long runningTtlMillis = 15000L;

        private Long takeoverDetectMillis = 10000L;

        private Long resultTtlMillis = 600000L;

        private Long failedResultTtlMillis = 60000L;

        private Integer compressionThresholdBytes = 4096;

        private String compressionCodec = "gzip";

        private Boolean l1CacheEnabled = false;

        private Long l1CacheTtlMillis = 30000L;

        StageFlightPolicy merge(StageFlightPolicy override) {
            StageFlightPolicy merged = new StageFlightPolicy();
            if (override == null) {
                return merged;
            }
            merged.heartbeatIntervalMillis = positiveLong(override.heartbeatIntervalMillis, heartbeatIntervalMillis);
            merged.runningTtlMillis = positiveLong(override.runningTtlMillis, runningTtlMillis);
            merged.takeoverDetectMillis = positiveLong(override.takeoverDetectMillis, takeoverDetectMillis);
            merged.resultTtlMillis = positiveLong(override.resultTtlMillis, resultTtlMillis);
            merged.failedResultTtlMillis = positiveLong(override.failedResultTtlMillis, failedResultTtlMillis);
            merged.compressionThresholdBytes = positiveInt(override.compressionThresholdBytes, compressionThresholdBytes);
            merged.compressionCodec = override.compressionCodec == null || override.compressionCodec.isBlank()
                    ? compressionCodec
                    : override.compressionCodec.trim().toLowerCase(Locale.ROOT);
            merged.l1CacheEnabled = override.l1CacheEnabled != null ? override.l1CacheEnabled : l1CacheEnabled;
            merged.l1CacheTtlMillis = positiveLong(override.l1CacheTtlMillis, l1CacheTtlMillis);
            return merged;
        }

        private Long positiveLong(Long value, Long defaultValue) {
            return value != null && value > 0 ? value : defaultValue;
        }

        private Integer positiveInt(Integer value, Integer defaultValue) {
            return value != null && value > 0 ? value : defaultValue;
        }
    }
}
