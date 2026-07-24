package com.hewei.hzyjy.xunzhi.common.config.thread;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolDynamicRefreshServiceTest {

    private final ThreadPoolRegistry registry = new ThreadPoolRegistry();
    private final ThreadPoolDynamicRefreshService refreshService = new ThreadPoolDynamicRefreshService(registry);
    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(
                4,
                8,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50)
        );
        registry.register("query", executor, 50);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRefreshCoreAndMaxPoolSize() {
        ThreadPoolStateResponse state = refreshService.refresh("query", 6, 12, null);

        assertEquals(6, state.getCorePoolSize());
        assertEquals(12, state.getMaximumPoolSize());
        assertEquals(6, executor.getCorePoolSize());
        assertEquals(12, executor.getMaximumPoolSize());
    }

    @Test
    void shouldRaiseMaxBeforeCoreWhenIncreasingBeyondCurrentMax() {
        ThreadPoolStateResponse state = refreshService.refresh("query", 16, 16, null);

        assertEquals(16, state.getCorePoolSize());
        assertEquals(16, state.getMaximumPoolSize());
    }

    @Test
    void shouldRefreshKeepAliveSeconds() {
        ThreadPoolStateResponse state = refreshService.refresh("query", null, null, 120);

        assertEquals(120, state.getKeepAliveSeconds());
        assertEquals(120, executor.getKeepAliveTime(TimeUnit.SECONDS));
    }

    @Test
    void shouldRejectWhenNoParameterProvided() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> refreshService.refresh("query", null, null, null)
        );
        assertTrue(ex.getMessage().contains("至少需要提供"));
    }

    @Test
    void shouldRejectWhenCoreGreaterThanMax() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> refreshService.refresh("query", 20, 10, null)
        );
        assertTrue(ex.getMessage().contains("不能大于"));
    }

    @Test
    void shouldGetStateByName() {
        ThreadPoolStateResponse state = refreshService.getState("query");
        assertEquals("query", state.getPoolName());
        assertEquals(4, state.getCorePoolSize());
    }
}
