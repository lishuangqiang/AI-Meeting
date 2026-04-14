package com.hewei.hzyjy.xunzhi.common.biz.user;

import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.common.config.user.UserFlowRiskControlConfiguration;
import com.hewei.hzyjy.xunzhi.common.convention.result.Results;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitKeyResolver;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitPolicy;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Global request rate-limit filter.
 */
@Slf4j
@RequiredArgsConstructor
public class UserFlowRiskControlFilter implements Filter {
    private static final String FLOW_LIMIT_ERROR_CODE = "A000300";
    private static final String FLOW_LIMIT_ERROR_MESSAGE = "当前系统繁忙，请稍后再试";

    private final UserFlowRiskControlConfiguration userFlowRiskControlConfiguration;
    private final RequestRateLimitService requestRateLimitService;
    private final RequestRateLimitKeyResolver requestRateLimitKeyResolver;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (shouldSkip(httpRequest.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = requestRateLimitKeyResolver.resolve(httpRequest);
        RequestRateLimitPolicy policy = userFlowRiskControlConfiguration.resolvePolicy(httpRequest.getRequestURI());
        boolean allowed;
        try {
            allowed = requestRateLimitService.tryAcquire(key, policy);
        } catch (Throwable ex) {
            log.error("Request rate limiting failed, key={}, bucket={}", key, policy.bucketName(), ex);
            writeFailure((HttpServletResponse) response, policy.timeWindowSeconds());
            return;
        }

        if (!allowed) {
            log.warn("Request rejected by rate limiter, key={}, bucket={}, uri={}",
                    key, policy.bucketName(), httpRequest.getRequestURI());
            writeFailure((HttpServletResponse) response, policy.timeWindowSeconds());
            return;
        }

        RequestRateLimitPolicy aiPolicy = userFlowRiskControlConfiguration.resolveAiPolicy(httpRequest.getRequestURI());
        if (aiPolicy != null) {
            boolean aiAllowed;
            try {
                aiAllowed = requestRateLimitService.tryAcquire(key, aiPolicy);
            } catch (Throwable ex) {
                log.error("AI bucket rate limiting failed, key={}, bucket={}", key, aiPolicy.bucketName(), ex);
                writeFailure((HttpServletResponse) response, aiPolicy.timeWindowSeconds());
                return;
            }
            if (!aiAllowed) {
                log.warn("Request rejected by AI bucket limiter, key={}, bucket={}, uri={}",
                        key, aiPolicy.bucketName(), httpRequest.getRequestURI());
                writeFailure((HttpServletResponse) response, aiPolicy.timeWindowSeconds());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldSkip(String requestUri) {
        if (requestUri == null || userFlowRiskControlConfiguration.getSkipPathPrefixes() == null) {
            return false;
        }
        return userFlowRiskControlConfiguration.getSkipPathPrefixes().stream()
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .anyMatch(requestUri::startsWith);
    }

    private void writeFailure(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        //这个版本暂时没有429状态码，只能手写
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(Math.max(1L, retryAfterSeconds)));
        try (PrintWriter writer = response.getWriter()) {
            writer.print(JSON.toJSONString(Results.failure(FLOW_LIMIT_ERROR_CODE, FLOW_LIMIT_ERROR_MESSAGE)));
        }
    }
}
