package com.hewei.hzyjy.xunzhi.common.config.thread;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池运行时动态刷新服务。
 * <p>
 * 仅支持调整 core / max / keepAlive；队列容量受 {@link java.util.concurrent.LinkedBlockingQueue}
 * 创建时固定限制，无法在不重建线程池的前提下热更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreadPoolDynamicRefreshService {

    private final ThreadPoolRegistry threadPoolRegistry;

    public List<ThreadPoolStateResponse> listStates() {
        return threadPoolRegistry.listStates();
    }

    public ThreadPoolStateResponse getState(String poolName) {
        return threadPoolRegistry.require(poolName).snapshot();
    }

    /**
     * 动态调整指定线程池参数。
     *
     * @param poolName          池名称
     * @param corePoolSize      新的核心线程数，null 表示不改
     * @param maxPoolSize       新的最大线程数，null 表示不改
     * @param keepAliveSeconds  空闲线程存活秒数，null 表示不改
     */
    public ThreadPoolStateResponse refresh(
            String poolName,
            Integer corePoolSize,
            Integer maxPoolSize,
            Integer keepAliveSeconds) {
        if (corePoolSize == null && maxPoolSize == null && keepAliveSeconds == null) {
            throw new IllegalArgumentException("至少需要提供 corePoolSize、maxPoolSize、keepAliveSeconds 之一");
        }

        ThreadPoolRegistry.RegisteredPool registered = threadPoolRegistry.require(poolName);
        ThreadPoolExecutor executor = registered.getExecutor();

        int currentCore = executor.getCorePoolSize();
        int currentMax = executor.getMaximumPoolSize();
        int targetCore = corePoolSize != null ? corePoolSize : currentCore;
        int targetMax = maxPoolSize != null ? maxPoolSize : currentMax;

        validatePoolSize(targetCore, targetMax);
        if (keepAliveSeconds != null && keepAliveSeconds < 0) {
            throw new IllegalArgumentException("keepAliveSeconds 不能为负数");
        }

        // JDK 约束：增大 core 时若超过当前 max，必须先抬 max；缩小 max 时若低于当前 core，必须先降 core。
        if (targetCore <= executor.getMaximumPoolSize()) {
            if (targetCore != currentCore) {
                executor.setCorePoolSize(targetCore);
            }
            if (targetMax != currentMax) {
                executor.setMaximumPoolSize(targetMax);
            }
        } else {
            if (targetMax != currentMax) {
                executor.setMaximumPoolSize(targetMax);
            }
            if (targetCore != currentCore) {
                executor.setCorePoolSize(targetCore);
            }
        }

        if (keepAliveSeconds != null) {
            executor.setKeepAliveTime(keepAliveSeconds, TimeUnit.SECONDS);
        }

        ThreadPoolStateResponse state = registered.snapshot();
        log.info(
                "线程池动态刷新完成, pool={}, core={}->{}, max={}->{}, keepAliveSeconds={}",
                poolName,
                currentCore,
                state.getCorePoolSize(),
                currentMax,
                state.getMaximumPoolSize(),
                state.getKeepAliveSeconds()
        );
        return state;
    }

    private void validatePoolSize(int corePoolSize, int maxPoolSize) {
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize 必须大于 0");
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize 必须大于 0");
        }
        if (corePoolSize > maxPoolSize) {
            throw new IllegalArgumentException(
                    "corePoolSize(" + corePoolSize + ") 不能大于 maxPoolSize(" + maxPoolSize + ")");
        }
    }
}
