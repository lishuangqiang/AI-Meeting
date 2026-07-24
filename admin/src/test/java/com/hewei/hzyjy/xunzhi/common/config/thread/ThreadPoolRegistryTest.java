package com.hewei.hzyjy.xunzhi.common.config.thread;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolRegistryTest {

    private final ThreadPoolRegistry registry = new ThreadPoolRegistry();
    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRegisterAndSnapshotState() {
        executor = newExecutor(4, 8, 100);
        registry.register("query", executor, 100);

        ThreadPoolStateResponse state = registry.require("query").snapshot();

        assertEquals("query", state.getPoolName());
        assertEquals(4, state.getCorePoolSize());
        assertEquals(8, state.getMaximumPoolSize());
        assertEquals(100, state.getConfiguredQueueCapacity());
        assertEquals(100, state.getQueueRemainingCapacity());
    }

    @Test
    void shouldListStatesSortedByName() {
        ThreadPoolExecutor cpu = newExecutor(2, 4, 10);
        ThreadPoolExecutor query = newExecutor(3, 6, 20);
        try {
            registry.register("query", query, 20);
            registry.register("cpu-compute", cpu, 10);

            List<ThreadPoolStateResponse> states = registry.listStates();

            assertEquals(2, states.size());
            assertEquals("cpu-compute", states.get(0).getPoolName());
            assertEquals("query", states.get(1).getPoolName());
        } finally {
            cpu.shutdownNow();
            query.shutdownNow();
        }
    }

    @Test
    void shouldThrowWhenPoolMissing() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
        assertTrue(ex.getMessage().contains("未找到线程池"));
    }

    private ThreadPoolExecutor newExecutor(int core, int max, int queueCapacity) {
        return new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity)
        );
    }
}
