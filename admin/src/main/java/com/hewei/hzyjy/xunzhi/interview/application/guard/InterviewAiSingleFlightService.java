package com.hewei.hzyjy.xunzhi.interview.application.guard;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Short-living single-flight deduplication for AI requests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewAiSingleFlightService {

    private final InterviewAiSingleFlightConfiguration configuration;
    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, FlightEntry> flights = new ConcurrentHashMap<>();

    public <T> T execute(String key, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        if (!Boolean.TRUE.equals(configuration.getEnable()) || StrUtil.isBlank(key)) {
            meterRegistry.counter("ai_singleflight_miss_total").increment();
            return supplier.get();
        }

        long now = System.currentTimeMillis();
        long ttlMillis = resolveTtlMillis();
        AtomicBoolean newFlight = new AtomicBoolean(false);
        FlightEntry entry = flights.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expireAtMillis <= now) {
                newFlight.set(true);
                return new FlightEntry(new CompletableFuture<>(), now + ttlMillis);
            }
            return existing;
        });

        if (newFlight.get()) {
            meterRegistry.counter("ai_singleflight_miss_total").increment();
            try {
                T value = supplier.get();
                entry.resultFuture.complete(value);
                return value;
            } catch (Throwable ex) {
                entry.resultFuture.completeExceptionally(ex);
                flights.remove(key, entry);
                throw ex;
            } finally {
                cleanupExpired(now);
            }
        }

        meterRegistry.counter("ai_singleflight_hit_total").increment();
        try {
            @SuppressWarnings("unchecked")
            T reused = (T) entry.resultFuture.get(resolveWaitTimeoutMillis(), TimeUnit.MILLISECONDS);
            return reused;
        } catch (TimeoutException ex) {
            flights.remove(key, entry);
            throw new CompletionException(new RejectedExecutionException("single-flight wait timeout", ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        } catch (ExecutionException ex) {
            throw rethrow(ex.getCause());
        }
    }

    private RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(cause);
    }

    private long resolveTtlMillis() {
        Long configured = configuration.getTtlMillis();
        return configured != null && configured > 0 ? configured : 4000L;
    }

    private long resolveWaitTimeoutMillis() {
        Long configured = configuration.getWaitTimeoutMillis();
        return configured != null && configured > 0 ? configured : 5000L;
    }

    private void cleanupExpired(long nowMillis) {
        Integer configured = configuration.getCleanupThreshold();
        int threshold = configured != null && configured > 0 ? configured : 256;
        if (flights.size() < threshold) {
            return;
        }
        flights.entrySet().removeIf(entry -> entry.getValue().expireAtMillis <= nowMillis);
    }

    private record FlightEntry(CompletableFuture<Object> resultFuture, long expireAtMillis) {
    }
}
