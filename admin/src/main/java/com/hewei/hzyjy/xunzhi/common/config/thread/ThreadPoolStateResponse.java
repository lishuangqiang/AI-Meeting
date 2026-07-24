package com.hewei.hzyjy.xunzhi.common.config.thread;

import lombok.Builder;
import lombok.Data;

/**
 * 线程池运行时状态快照，用于 Actuator 查询与动态调参后回显。
 */
@Data
@Builder
public class ThreadPoolStateResponse {

    /**
     * 线程池名称（与注册表中的 key 一致）。
     */
    private String poolName;

    private int corePoolSize;

    private int maximumPoolSize;

    private int activeCount;

    private int poolSize;

    private int largestPoolSize;

    private long completedTaskCount;

    private long taskCount;

    /**
     * 当前队列中等待的任务数。
     */
    private int queueSize;

    /**
     * 队列剩余容量；无界队列时可能为 {@link Integer#MAX_VALUE}。
     */
    private int queueRemainingCapacity;

    /**
     * 创建时配置的队列容量；无法从运行时无界队列可靠推断时可为 null。
     */
    private Integer configuredQueueCapacity;

    private long keepAliveSeconds;
}
