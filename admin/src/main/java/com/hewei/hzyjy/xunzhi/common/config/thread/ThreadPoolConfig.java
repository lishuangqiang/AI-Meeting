package com.hewei.hzyjy.xunzhi.common.config.thread;

import com.hewei.hzyjy.xunzhi.toolkit.Threads;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 按工作负载拆分的共享线程池：
 * - general：通用异步任务
 * - interview AI I/O：面试相关 AI 调用
 * - CPU：偏本地计算
 * - query：查询/聚合类异步任务
 * - scheduled：定时调度
 * <p>
 * 创建后会将底层 {@link ThreadPoolExecutor} 登记到 {@link ThreadPoolRegistry}，
 * 以便运行时观测与动态调整（不影响现有 Bean 注入方式）。
 */
@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfig {

    public static final String POOL_GENERAL = "general";
    public static final String POOL_INTERVIEW_AI_IO = "interview-ai-io";
    public static final String POOL_CPU_COMPUTE = "cpu-compute";
    public static final String POOL_QUERY = "query";
    public static final String POOL_SCHEDULED = "scheduled";

    private final ApplicationThreadPoolProperties properties;
    private final MeterRegistry meterRegistry;
    private final ThreadPoolRegistry threadPoolRegistry;

    @Bean(name = "threadPoolTaskExecutor")
    public ThreadPoolTaskExecutor threadPoolTaskExecutor() {
        ApplicationThreadPoolProperties.Pool pool = properties.getGeneral();
        ThreadPoolTaskExecutor executor = buildTaskExecutor(pool);
        threadPoolRegistry.register(
                POOL_GENERAL,
                executor.getThreadPoolExecutor(),
                positive(pool == null ? null : pool.getQueueCapacity(), 1000)
        );
        return executor;
    }

    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor(@Qualifier("threadPoolTaskExecutor") ThreadPoolTaskExecutor threadPoolTaskExecutor) {
        return threadPoolTaskExecutor;
    }

    @Bean(name = "interviewAiIoExecutor", destroyMethod = "shutdown")
    public ExecutorService interviewAiIoExecutor() {
        return buildExecutorService(POOL_INTERVIEW_AI_IO, properties.getAiIo());
    }

    @Bean(name = "cpuComputeExecutor", destroyMethod = "shutdown")
    public ExecutorService cpuComputeExecutor() {
        return buildExecutorService(POOL_CPU_COMPUTE, properties.getCpu());
    }

    @Bean(name = "queryExecutor", destroyMethod = "shutdown")
    public ExecutorService queryExecutor() {
        return buildExecutorService(POOL_QUERY, properties.getQuery());
    }

    @Bean(name = "scheduledExecutorService")
    protected ScheduledExecutorService scheduledExecutorService() {
        int poolSize = positive(properties.getScheduledPoolSize(), 8);
        String prefix = normalizePrefix(properties.getScheduledThreadNamePrefix(), "xunzhi-schedule-");
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                poolSize,
                new BasicThreadFactory.Builder().namingPattern(prefix + "%d").daemon(true).build()
        ) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable throwable) {
                super.afterExecute(runnable, throwable);
                Threads.printException(runnable, throwable);
            }
        };
        // ScheduledThreadPoolExecutor 使用 DelayedWorkQueue，无固定有界容量可配置。
        threadPoolRegistry.register(POOL_SCHEDULED, executor, null);
        return executor;
    }

    private ThreadPoolTaskExecutor buildTaskExecutor(ApplicationThreadPoolProperties.Pool pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(positive(pool == null ? null : pool.getCorePoolSize(), 16));
        executor.setMaxPoolSize(positive(pool == null ? null : pool.getMaxPoolSize(), 64));
        executor.setQueueCapacity(positive(pool == null ? null : pool.getQueueCapacity(), 1000));
        executor.setKeepAliveSeconds(positive(pool == null ? null : pool.getKeepAliveSeconds(), 60));
        executor.setThreadNamePrefix(normalizePrefix(pool == null ? null : pool.getThreadNamePrefix(), "xunzhi-async-"));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private ExecutorService buildExecutorService(String poolName, ApplicationThreadPoolProperties.Pool pool) {
        int coreSize = positive(pool == null ? null : pool.getCorePoolSize(), 8);
        int maxSize = Math.max(coreSize, positive(pool == null ? null : pool.getMaxPoolSize(), coreSize));
        int queueCapacity = positive(pool == null ? null : pool.getQueueCapacity(), 200);
        long keepAliveSeconds = positive(pool == null ? null : pool.getKeepAliveSeconds(), 60);
        String prefix = normalizePrefix(pool == null ? null : pool.getThreadNamePrefix(), "xunzhi-exec-");
        ThreadFactory threadFactory = new BasicThreadFactory.Builder()
                .namingPattern(prefix + "%d")
                .daemon(false)
                .build();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                resolveRejectPolicy(poolName)
        ) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable throwable) {
                super.afterExecute(runnable, throwable);
                Threads.printException(runnable, throwable);
            }
        };
        // 必须在 Micrometer 包装前登记原始实例，否则运行时无法 setCore/setMax。
        threadPoolRegistry.register(poolName, executor, queueCapacity);
        return ExecutorServiceMetrics.monitor(
                meterRegistry,
                executor,
                "xunzhi_thread_pool",
                Tags.of("pool", poolName)
        );
    }

    private int positive(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private ThreadPoolExecutor.AbortPolicy abortPolicy() {
        return new ThreadPoolExecutor.AbortPolicy();
    }

    private ThreadPoolExecutor.CallerRunsPolicy callerRunsPolicy() {
        return new ThreadPoolExecutor.CallerRunsPolicy();
    }

    private java.util.concurrent.RejectedExecutionHandler resolveRejectPolicy(String poolName) {
        if (POOL_INTERVIEW_AI_IO.equals(poolName)) {
            return abortPolicy();
        }
        return callerRunsPolicy();
    }

    private String normalizePrefix(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
