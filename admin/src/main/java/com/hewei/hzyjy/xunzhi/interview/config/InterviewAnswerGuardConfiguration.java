package com.hewei.hzyjy.xunzhi.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Guard configuration for interview answer concurrency and idempotency.
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.answer-guard")
public class InterviewAnswerGuardConfiguration {

    /**
     * Lock lease time in seconds for session + question lock.
     * <=0 means using Redisson watchdog mode.
     */
    private Long lockExpireSeconds = 120L;

    /**
     * Use Redisson watchdog mode when lockExpireSeconds <= 0.
     */
    private Boolean lockWatchdogEnabled = true;

    /**
     * Processing marker ttl in seconds.
     */
    private Long processingExpireSeconds = 120L;

    /**
     * Long-tail protection ttl in seconds for slow paths.
     */
    private Long processingLongTailExpireSeconds = 300L;

    /**
     * Replay result ttl in hours.
     */
    private Long replayExpireHours = 24L;

    /**
     * Lock wait time in milliseconds.
     */
    private Long lockWaitMillis = 0L;
}
