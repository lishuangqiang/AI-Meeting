package com.hewei.hzyjy.xunzhi.common.config.xunfei;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Validates required Xunfei credentials at startup.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "xunzhi-agent.security", name = "require-xunfei-secrets", havingValue = "true", matchIfMissing = true)
public class XunfeiSecretStartupValidator {

    private final XunfeiLatProperties properties;

    @PostConstruct
    public void validate() {
        if (StrUtil.isBlank(properties.getAppId())
                || StrUtil.isBlank(properties.getApiKey())
                || StrUtil.isBlank(properties.getApiSecret())) {
            throw new IllegalStateException("Xunfei credentials are required: xunfei.lat-key.app-id/api-key/api-secret");
        }
    }
}
