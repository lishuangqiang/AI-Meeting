package com.hewei.hzyjy.xunzhi.common.config.user;

import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Request rate-limit configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.flow-limit")
public class UserFlowRiskControlConfiguration {

    /**
     * Whether request rate limiting is enabled.
     */
    private Boolean enable = true;

    /**
     * Preferred window size in seconds.
     */
    private Long timeWindowSeconds = 1L;

    /**
     * Legacy property kept for compatibility with existing local configs.
     */
    private Long timeWindow;

    /**
     * Max number of permits allowed in the configured window.
     */
    private Long maxAccessCount = 20L;

    /**
     * Number of permits consumed per request.
     */
    private Long requestedTokens = 1L;

    /**
     * Answer endpoints (high frequency, medium cost) bucket.
     */
    private Long interviewAnswerMaxAccessCount = 8L;

    private Long interviewAnswerTimeWindowSeconds = 1L;

    /**
     * Heavy endpoints (resume extraction, demeanor evaluation) bucket.
     */
    private Long interviewHeavyMaxAccessCount = 2L;

    private Long interviewHeavyTimeWindowSeconds = 1L;

    /**
     * Other interview endpoints (query/restore/report) bucket.
     */
    private Long interviewReadMaxAccessCount = 15L;

    private Long interviewReadTimeWindowSeconds = 1L;

    /**
     * Dedicated AI-call bucket for interview endpoints that trigger external workflows.
     */
    private Long interviewAiCallMaxAccessCount = 6L;

    private Long interviewAiCallTimeWindowSeconds = 1L;

    /**
     * Shared Redis key prefix for rate limiters.
     */
    private String keyPrefix = "xunzhi-agent:rate-limit";

    /**
     * Paths that should bypass request rate limiting.
     */
    private List<String> skipPathPrefixes = new ArrayList<>(List.of("/actuator", "/error"));

    public Long getTimeWindowSeconds() {
        return timeWindowSeconds != null ? timeWindowSeconds : (timeWindow != null ? timeWindow : 1L);
    }

    public RequestRateLimitPolicy resolvePolicy(String requestUri) {
        String uri = requestUri == null ? "" : requestUri;
        if (isAnswerEndpoint(uri)) {
            return buildPolicy("interview-answer", interviewAnswerMaxAccessCount, interviewAnswerTimeWindowSeconds);
        }
        if (isHeavyEndpoint(uri)) {
            return buildPolicy("interview-heavy", interviewHeavyMaxAccessCount, interviewHeavyTimeWindowSeconds);
        }
        if (uri.startsWith("/api/xunzhi/v1/interview")) {
            return buildPolicy("interview-read", interviewReadMaxAccessCount, interviewReadTimeWindowSeconds);
        }
        return buildPolicy("default", maxAccessCount, getTimeWindowSeconds());
    }

    public RequestRateLimitPolicy resolveAiPolicy(String requestUri) {
        String uri = requestUri == null ? "" : requestUri;
        if (!isAiEndpoint(uri)) {
            return null;
        }
        return buildPolicy("interview-ai", interviewAiCallMaxAccessCount, interviewAiCallTimeWindowSeconds);
    }

    private boolean isAnswerEndpoint(String uri) {
        return uri.contains("/interview/answer");
    }

    private boolean isHeavyEndpoint(String uri) {
        return uri.endsWith("/interview-questions") || uri.endsWith("/demeanor-evaluation");
    }

    private boolean isAiEndpoint(String uri) {
        return isAnswerEndpoint(uri) || isHeavyEndpoint(uri);
    }

    private RequestRateLimitPolicy buildPolicy(String bucketName, Long maxAccess, Long windowSeconds) {
        return new RequestRateLimitPolicy(
                bucketName,
                positiveOrDefault(maxAccess, positiveOrDefault(maxAccessCount, 20L)),
                positiveOrDefault(windowSeconds, positiveOrDefault(getTimeWindowSeconds(), 1L)),
                positiveOrDefault(requestedTokens, 1L)
        );
    }

    private long positiveOrDefault(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }
}
