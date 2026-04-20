package com.hewei.hzyjy.xunzhi.common.biz.user;

import com.hewei.hzyjy.xunzhi.common.config.user.UserFlowRiskControlConfiguration;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitKeyResolver;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitPolicy;
import com.hewei.hzyjy.xunzhi.common.ratelimit.RequestRateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserFlowRiskControlFilterTest {

    @Test
    void shouldPassRequestWhenPermitIsAvailable() throws Exception {
        RequestRateLimitService requestRateLimitService = mock(RequestRateLimitService.class);
        RequestRateLimitKeyResolver requestRateLimitKeyResolver = mock(RequestRateLimitKeyResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        UserFlowRiskControlFilter filter = new UserFlowRiskControlFilter(
                buildConfiguration(),
                requestRateLimitService,
                requestRateLimitKeyResolver
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/xunzhi/v1/ai/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(requestRateLimitKeyResolver.resolve(request)).thenReturn("user:alice");
        when(requestRateLimitService.tryAcquire(eq("user:alice"), any(RequestRateLimitPolicy.class))).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldReturnFlowLimitErrorWhenPermitIsRejected() throws Exception {
        RequestRateLimitService requestRateLimitService = mock(RequestRateLimitService.class);
        RequestRateLimitKeyResolver requestRateLimitKeyResolver = mock(RequestRateLimitKeyResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        UserFlowRiskControlFilter filter = new UserFlowRiskControlFilter(
                buildConfiguration(),
                requestRateLimitService,
                requestRateLimitKeyResolver
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/xunzhi/v1/interview/answer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(requestRateLimitKeyResolver.resolve(request)).thenReturn("user:alice");
        when(requestRateLimitService.tryAcquire(eq("user:alice"), any(RequestRateLimitPolicy.class))).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("\"code\":\"A000300\""));
    }

    @Test
    void shouldSkipConfiguredPaths() throws Exception {
        RequestRateLimitService requestRateLimitService = mock(RequestRateLimitService.class);
        RequestRateLimitKeyResolver requestRateLimitKeyResolver = mock(RequestRateLimitKeyResolver.class);
        FilterChain filterChain = mock(FilterChain.class);
        UserFlowRiskControlConfiguration configuration = buildConfiguration();
        configuration.setSkipPathPrefixes(List.of("/actuator", "/error"));
        UserFlowRiskControlFilter filter = new UserFlowRiskControlFilter(
                configuration,
                requestRateLimitService,
                requestRateLimitKeyResolver
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(requestRateLimitService, requestRateLimitKeyResolver);
    }

    private UserFlowRiskControlConfiguration buildConfiguration() {
        UserFlowRiskControlConfiguration configuration = new UserFlowRiskControlConfiguration();
        configuration.setEnable(true);
        configuration.setKeyPrefix("xunzhi-agent:rate-limit");
        configuration.setTimeWindowSeconds(1L);
        configuration.setMaxAccessCount(20L);
        configuration.setRequestedTokens(1L);
        configuration.setSkipPathPrefixes(List.of("/actuator", "/error"));
        return configuration;
    }
}
