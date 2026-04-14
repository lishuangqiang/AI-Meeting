package com.hewei.hzyjy.xunzhi.interview.application.guard;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiGuardConfiguration;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified AI guard service with timeout/circuit-breaker/bulkhead/retry.
 */
@Service
@Slf4j
public class AiCallGuardService {

    private final InterviewAiGuardConfiguration configuration;
    private final MeterRegistry meterRegistry;
    private final ExecutorService aiIoExecutor;

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Retry> retries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TimeLimiter> timeLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    public AiCallGuardService(
            InterviewAiGuardConfiguration configuration,
            MeterRegistry meterRegistry,
            @Qualifier("interviewAiIoExecutor") ExecutorService aiIoExecutor) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.aiIoExecutor = aiIoExecutor;
    }

    public <T> T execute(String stage, String requestKey, Callable<T> action) {
        String safeStage = StrUtil.isNotBlank(stage) ? stage : "interview-default";
        long startNano = System.nanoTime();
        try {
            T result;
            if (Boolean.TRUE.equals(configuration.getEnable())) {
                result = guardedExecute(safeStage, action);
            } else {
                result = action.call();
            }
            recordMetrics(safeStage, "success", System.nanoTime() - startNano);
            return result;
        } catch (Throwable ex) {
            InterviewAiGuardException guardException = classifyException(ex, safeStage);
            String resultTag = guardException.getErrorCode().name().toLowerCase();
            recordMetrics(safeStage, resultTag, System.nanoTime() - startNano);
            if (guardException.getErrorCode() == InterviewAiGuardErrorCode.AI_OVERLOADED
                    && isBulkheadReject(unwrap(ex))) {
                meterRegistry.counter("ai_bulkhead_rejected_total", "stage", safeStage).increment();
            }
            log.warn(
                    "AI guarded call failed, stage={}, requestKey={}, code={}, message={}",
                    safeStage,
                    requestKey,
                    guardException.getErrorCode(),
                    guardException.getMessage()
            );
            throw guardException;
        }
    }

    private <T> T guardedExecute(String stage, Callable<T> action) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(stage, this::newCircuitBreaker);
        Bulkhead bulkhead = bulkheads.computeIfAbsent(stage, this::newBulkhead);
        Retry retry = retries.computeIfAbsent(stage, this::newRetry);

        Callable<T> decorated = CircuitBreaker.decorateCallable(
                circuitBreaker,
                Bulkhead.decorateCallable(
                        bulkhead,
                        Retry.decorateCallable(
                                retry,
                                () -> callWithTimeLimiter(stage, action)
                        )
                )
        );
        return decorated.call();
    }

    private <T> T callWithTimeLimiter(String stage, Callable<T> action) throws Exception {
        TimeLimiter timeLimiter = timeLimiters.computeIfAbsent(stage, this::newTimeLimiter);
        Callable<T> timeLimitedCall = TimeLimiter.decorateFutureSupplier(
                timeLimiter,
                () -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, aiIoExecutor)
        );
        return timeLimitedCall.call();
    }

    private CircuitBreaker newCircuitBreaker(String stage) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(resolveFailureRateThreshold())
                .slidingWindowSize(resolveSlidingWindowSize())
                .permittedNumberOfCallsInHalfOpenState(resolveHalfOpenProbeCount())
                .waitDurationInOpenState(Duration.ofMillis(resolveOpenWaitMillis()))
                .minimumNumberOfCalls(Math.min(10, resolveSlidingWindowSize()))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of(stage, config);
        AtomicInteger stateGauge = new AtomicInteger(mapCircuitState(breaker.getState()));
        AtomicInteger existing = circuitStateGauges.putIfAbsent(stage, stateGauge);
        AtomicInteger gaugeTarget = existing != null ? existing : stateGauge;
        if (existing == null) {
            meterRegistry.gauge("ai_circuit_state", Tags.of("stage", stage), gaugeTarget, AtomicInteger::get);
        }
        breaker.getEventPublisher().onStateTransition(event ->
                gaugeTarget.set(mapCircuitState(event.getStateTransition().getToState())));
        return breaker;
    }

    private Bulkhead newBulkhead(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int maxConcurrent = policy.getMaxConcurrentCalls() != null && policy.getMaxConcurrentCalls() > 0
                ? policy.getMaxConcurrentCalls()
                : 20;
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return Bulkhead.of(stage, config);
    }

    private Retry newRetry(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int retryCount = policy.getRetryCount() != null && policy.getRetryCount() >= 0 ? policy.getRetryCount() : 0;
        long waitMillis = policy.getRetryWaitMillis() != null && policy.getRetryWaitMillis() >= 0
                ? policy.getRetryWaitMillis()
                : 0L;
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, retryCount + 1))
                .waitDuration(Duration.ofMillis(waitMillis))
                .retryOnException(this::isRetriable)
                .ignoreExceptions(CallNotPermittedException.class, BulkheadFullException.class, InterviewAiGuardException.class)
                .build();
        return Retry.of(stage, config);
    }

    private TimeLimiter newTimeLimiter(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        long timeoutMillis = policy.getTimeoutMillis() != null && policy.getTimeoutMillis() > 0
                ? policy.getTimeoutMillis()
                : 5000L;
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of(stage, config);
    }

    private boolean isRetriable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause instanceof TimeoutException || cause instanceof IOException;
    }

    private InterviewAiGuardException classifyException(Throwable throwable, String stage) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof InterviewAiGuardException guardException) {
            return guardException;
        }

        InterviewAiGuardErrorCode code;
        String message;
        if (cause instanceof TimeoutException) {
            code = InterviewAiGuardErrorCode.AI_TIMEOUT;
            message = "AI_TIMEOUT: ai call timed out, please retry";
        } else if (isOverloaded(cause)) {
            code = InterviewAiGuardErrorCode.AI_OVERLOADED;
            message = "AI_OVERLOADED: ai service is busy, please retry";
        } else {
            code = InterviewAiGuardErrorCode.AI_UNAVAILABLE;
            message = "AI_UNAVAILABLE: ai service is unavailable, please retry";
        }
        return new InterviewAiGuardException(code, stage, message, cause);
    }

    private boolean isOverloaded(Throwable cause) {
        return cause instanceof BulkheadFullException
                || cause instanceof RequestNotPermitted
                || cause instanceof RejectedExecutionException;
    }

    private boolean isBulkheadReject(Throwable cause) {
        return cause instanceof BulkheadFullException || cause instanceof RejectedExecutionException;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private void recordMetrics(String stage, String result, long elapsedNano) {
        meterRegistry.counter("ai_call_total", "stage", stage, "result", result).increment();
        meterRegistry.timer("ai_call_latency_ms", "stage", stage, "result", result)
                .record(Duration.ofNanos(elapsedNano));
    }

    private int mapCircuitState(CircuitBreaker.State state) {
        if (state == CircuitBreaker.State.CLOSED) {
            return 0;
        }
        if (state == CircuitBreaker.State.HALF_OPEN) {
            return 1;
        }
        return 2;
    }

    private float resolveFailureRateThreshold() {
        Float configured = configuration.getCircuitFailureRateThreshold();
        return configured != null && configured > 0 ? configured : 50F;
    }

    private int resolveSlidingWindowSize() {
        Integer configured = configuration.getCircuitSlidingWindowSize();
        return configured != null && configured > 0 ? configured : 50;
    }

    private int resolveHalfOpenProbeCount() {
        Integer configured = configuration.getCircuitPermittedCallsInHalfOpenState();
        return configured != null && configured > 0 ? configured : 10;
    }

    private long resolveOpenWaitMillis() {
        Long configured = configuration.getCircuitOpenStateWaitMillis();
        return configured != null && configured > 0 ? configured : 30000L;
    }
}
