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
 * Shared thread pools split by workload type:
 * - general async tasks
 * - interview AI I/O tasks
 * - CPU-intensive local compute tasks
 * - query/read-heavy async tasks
 */
@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfig {

    private final ApplicationThreadPoolProperties properties;
    private final MeterRegistry meterRegistry;

    @Bean(name = "threadPoolTaskExecutor")
    public ThreadPoolTaskExecutor threadPoolTaskExecutor() {
        return buildTaskExecutor(properties.getGeneral());
    }

    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor(@Qualifier("threadPoolTaskExecutor") ThreadPoolTaskExecutor threadPoolTaskExecutor) {
        return threadPoolTaskExecutor;
    }

    @Bean(name = "interviewAiIoExecutor", destroyMethod = "shutdown")
    public ExecutorService interviewAiIoExecutor() {
        return buildExecutorService("interview-ai-io", properties.getAiIo());
    }

    @Bean(name = "cpuComputeExecutor", destroyMethod = "shutdown")
    public ExecutorService cpuComputeExecutor() {
        return buildExecutorService("cpu-compute", properties.getCpu());
    }

    @Bean(name = "queryExecutor", destroyMethod = "shutdown")
    public ExecutorService queryExecutor() {
        return buildExecutorService("query", properties.getQuery());
    }

    @Bean(name = "scheduledExecutorService")
    protected ScheduledExecutorService scheduledExecutorService() {
        int poolSize = positive(properties.getScheduledPoolSize(), 8);
        String prefix = normalizePrefix(properties.getScheduledThreadNamePrefix(), "xunzhi-schedule-");
        return new ScheduledThreadPoolExecutor(
                poolSize,
                new BasicThreadFactory.Builder().namingPattern(prefix + "%d").daemon(true).build()
        ) {
            @Override
            protected void afterExecute(Runnable runnable, Throwable throwable) {
                super.afterExecute(runnable, throwable);
                Threads.printException(runnable, throwable);
            }
        };
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
        if ("interview-ai-io".equals(poolName)) {
            return abortPolicy();
        }
        return callerRunsPolicy();
    }

    private String normalizePrefix(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
