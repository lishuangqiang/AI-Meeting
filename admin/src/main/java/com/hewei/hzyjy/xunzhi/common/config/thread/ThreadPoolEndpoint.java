package com.hewei.hzyjy.xunzhi.common.config.thread;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 线程池可观测与动态调参 Actuator 端点。
 * <p>
 * 查询：{@code GET /actuator/threadpools}、{@code GET /actuator/threadpools/{poolName}}
 * 调参：{@code POST /actuator/threadpools/{poolName}}，body 可按需传 corePoolSize、maxPoolSize、keepAliveSeconds
 * （未传的字段保持原值；Actuator 要求可选参数标注 {@link Nullable}）。
 * <p>
 * 注意：当前项目 /actuator/** 默认无登录鉴权，生产环境请限制网络访问或另行加固。
 */
@Component
@Endpoint(id = "threadpools")
@RequiredArgsConstructor
public class ThreadPoolEndpoint {

    private final ThreadPoolDynamicRefreshService threadPoolDynamicRefreshService;

    @ReadOperation
    public List<ThreadPoolStateResponse> list() {
        return threadPoolDynamicRefreshService.listStates();
    }

    @ReadOperation
    public ThreadPoolStateResponse get(@Selector String poolName) {
        return threadPoolDynamicRefreshService.getState(poolName);
    }

    @WriteOperation
    public ThreadPoolStateResponse refresh(
            @Selector String poolName,
            @Nullable Integer corePoolSize,
            @Nullable Integer maxPoolSize,
            @Nullable Integer keepAliveSeconds) {
        return threadPoolDynamicRefreshService.refresh(poolName, corePoolSize, maxPoolSize, keepAliveSeconds);
    }
}
