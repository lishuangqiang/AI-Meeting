package com.hewei.hzyjy.xunzhi.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI single-flight and heavy-lock configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.ai-singleflight")
public class InterviewAiSingleFlightConfiguration {

    private Boolean enable = true;

    private Long ttlMillis = 4000L;

    private Long waitTimeoutMillis = 5000L;

    private Integer cleanupThreshold = 256;

    private Long heavyLockExpireSeconds = 45L;

    private Long heavyLockWaitMillis = 0L;
}
