package com.hewei.hzyjy.xunzhi.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retry-repair configuration for interview turn persistence.
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.turn-repair")
public class InterviewTurnRepairConfiguration {

    private Boolean enable = true;

    private Long fixedDelayMillis = 3000L;

    private Integer batchSize = 50;

    private Integer maxRetries = 6;
}
