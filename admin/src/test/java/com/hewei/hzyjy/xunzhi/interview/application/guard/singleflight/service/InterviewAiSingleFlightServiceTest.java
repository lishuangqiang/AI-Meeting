package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.service;

import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewAiSingleFlightServiceTest {

    @Test
    void shouldReuseInFlightResultForSameKey() throws Exception {
        InterviewAiSingleFlightService service = newService(5000L, 5000L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
                service.execute("k1", () -> {
                    started.countDown();
                    await(release);
                    return "value-1";
                })
        );
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletableFuture<String> second = CompletableFuture.supplyAsync(() ->
                service.execute("k1", () -> "value-2")
        );
        release.countDown();

        assertEquals("value-1", first.get(1, TimeUnit.SECONDS));
        assertEquals("value-1", second.get(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldTimeoutWhenWaitingInFlightTooLong() throws Exception {
        InterviewAiSingleFlightService service = newService(5000L, 60L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
                service.execute("k2", () -> {
                    started.countDown();
                    await(release);
                    return "value-1";
                })
        );
        assertTrue(started.await(1, TimeUnit.SECONDS));

        CompletionException ex = assertThrows(CompletionException.class,
                () -> service.execute("k2", () -> "value-2"));
        assertInstanceOf(RejectedExecutionException.class, ex.getCause());

        release.countDown();
        assertEquals("value-1", first.get(1, TimeUnit.SECONDS));
    }

    private InterviewAiSingleFlightService newService(Long ttlMillis, Long waitTimeoutMillis) {
        InterviewAiSingleFlightConfiguration configuration = new InterviewAiSingleFlightConfiguration();
        configuration.setEnable(true);
        configuration.setTtlMillis(ttlMillis);
        configuration.setWaitTimeoutMillis(waitTimeoutMillis);
        configuration.setCleanupThreshold(1);
        return new InterviewAiSingleFlightService(configuration, new SimpleMeterRegistry());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
