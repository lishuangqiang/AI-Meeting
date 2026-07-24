package com.hewei.hzyjy.xunzhi.common.config.thread;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 进程内线程池注册表。
 * <p>
 * 保存 {@link ThreadPoolExecutor} 原始引用，供运行时查询与动态调整。
 * 注意：Bean 对外暴露的可能是 Micrometer 包装后的 {@link java.util.concurrent.ExecutorService}，
 * 调参必须基于此处登记的底层实例。
 */
@Component
public class ThreadPoolRegistry {

    private final Map<String, RegisteredPool> pools = new ConcurrentHashMap<>();

    /**
     * 注册线程池实例。
     *
     * @param poolName                 池名称
     * @param executor                 底层线程池（未经过 Micrometer 包装）
     * @param configuredQueueCapacity  创建时配置的队列容量，可为 null
     */
    public void register(String poolName, ThreadPoolExecutor executor, Integer configuredQueueCapacity) {
        if (poolName == null || poolName.isBlank()) {
            throw new IllegalArgumentException("线程池名称不能为空");
        }
        if (executor == null) {
            throw new IllegalArgumentException("线程池实例不能为空: " + poolName);
        }
        pools.put(poolName, new RegisteredPool(poolName, executor, configuredQueueCapacity));
    }

    public Optional<RegisteredPool> find(String poolName) {
        if (poolName == null || poolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pools.get(poolName));
    }

    public RegisteredPool require(String poolName) {
        return find(poolName).orElseThrow(() ->
                new IllegalArgumentException("未找到线程池: " + poolName + "，可用: " + pools.keySet()));
    }

    public List<ThreadPoolStateResponse> listStates() {
        List<ThreadPoolStateResponse> states = new ArrayList<>(pools.size());
        for (RegisteredPool pool : pools.values()) {
            states.add(pool.snapshot());
        }
        states.sort((left, right) -> left.getPoolName().compareToIgnoreCase(right.getPoolName()));
        return states;
    }

    public Map<String, RegisteredPool> asMap() {
        return Collections.unmodifiableMap(pools);
    }

    /**
     * 已登记的线程池句柄。
     */
    public static final class RegisteredPool {

        private final String poolName;
        private final ThreadPoolExecutor executor;
        private final Integer configuredQueueCapacity;

        RegisteredPool(String poolName, ThreadPoolExecutor executor, Integer configuredQueueCapacity) {
            this.poolName = poolName;
            this.executor = executor;
            this.configuredQueueCapacity = configuredQueueCapacity;
        }

        public String getPoolName() {
            return poolName;
        }

        public ThreadPoolExecutor getExecutor() {
            return executor;
        }

        public Integer getConfiguredQueueCapacity() {
            return configuredQueueCapacity;
        }

        public ThreadPoolStateResponse snapshot() {
            return ThreadPoolStateResponse.builder()
                    .poolName(poolName)
                    .corePoolSize(executor.getCorePoolSize())
                    .maximumPoolSize(executor.getMaximumPoolSize())
                    .activeCount(executor.getActiveCount())
                    .poolSize(executor.getPoolSize())
                    .largestPoolSize(executor.getLargestPoolSize())
                    .completedTaskCount(executor.getCompletedTaskCount())
                    .taskCount(executor.getTaskCount())
                    .queueSize(executor.getQueue().size())
                    .queueRemainingCapacity(executor.getQueue().remainingCapacity())
                    .configuredQueueCapacity(configuredQueueCapacity)
                    .keepAliveSeconds(executor.getKeepAliveTime(TimeUnit.SECONDS))
                    .build();
        }
    }
}
