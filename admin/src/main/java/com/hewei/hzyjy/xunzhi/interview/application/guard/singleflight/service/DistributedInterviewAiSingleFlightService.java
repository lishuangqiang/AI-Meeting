package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.service;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightReplayLocalCache;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.cache.FlightResultSerializer;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightCoordinatorRepository;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightHeartbeatManager;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator.FlightNotificationService;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightAcquireResult;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightErrorType;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightMetaSnapshot;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightMode;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightOwnerContext;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStatus;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStoredResult;
import com.hewei.hzyjy.xunzhi.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 分布式 AI single-flight 核心服务，负责在集群内协调 owner 与 follower，
 * 完成请求抢占、结果复用、失败接管以及本地降级回退。
 *
 * @author 程序员牛肉
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedInterviewAiSingleFlightService {

    private final InterviewAiSingleFlightConfiguration configuration;
    private final InterviewAiSingleFlightService localSingleFlightService;
    private final FlightCoordinatorRepository flightCoordinatorRepository;
    private final FlightNotificationService flightNotificationService;
    private final FlightHeartbeatManager flightHeartbeatManager;
    private final FlightResultSerializer flightResultSerializer;
    private final FlightReplayLocalCache flightReplayLocalCache;

    public String execute(String stage, String requestKey, Supplier<String> supplier) {
        flightReplayLocalCache.refreshMaxSize(configuration.getL1CacheMaxSize());
        FlightMode mode = FlightMode.from(configuration.normalizedMode());
        if (!Boolean.TRUE.equals(configuration.getEnable()) || mode == FlightMode.LOCAL || !Boolean.TRUE.equals(configuration.getDistributedEnabled())) {
            return localSingleFlightService.execute(requestKey, supplier);
        }
        try {
            return executeDistributed(stage, requestKey, supplier);
        } catch (RuntimeException ex) {
            if (mode == FlightMode.HYBRID) {
                log.warn("Distributed single-flight fallback to local mode, stage={}, key={}, reason={}", stage, requestKey, ex.getMessage());
                return localSingleFlightService.execute(requestKey, supplier);
            }
            throw ex;
        }
    }

    private String executeDistributed(String stage, String requestKey, Supplier<String> supplier) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeRequestKey = StrUtil.blankToDefault(requestKey, safeStage + "|no-key");
        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy = configuration.resolveStagePolicy(safeStage);
        String localReplay = flightReplayLocalCache.get(safeStage, safeRequestKey);
        if (localReplay != null) {
            return localReplay;
        }

        long deadline = System.currentTimeMillis() + resolveFollowerMaxWaitMillis();
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            FlightAcquireResult acquireResult = flightCoordinatorRepository.acquireOrJoin(
                    safeStage,
                    safeRequestKey,
                    nodeId(),
                    extractSessionId(safeRequestKey),
                    policy
            );
            if (acquireResult == null || acquireResult.getAction() == null) {
                return localSingleFlightService.execute(safeRequestKey, supplier);
            }
            switch (acquireResult.getAction()) {
                case OWNER_NEW, OWNER_TAKEOVER -> {
                    return ownerExecute(safeStage, safeRequestKey, acquireResult.getOwnerToken(), supplier, policy);
                }
                case REPLAY_SUCCESS -> {
                    String replay = tryReadSuccessReplay(safeStage, safeRequestKey, policy);
                    if (replay != null) {
                        return replay;
                    }
                }
                case REPLAY_FAILURE -> throw replayFailure(acquireResult);
                case FOLLOWER_WAIT -> {
                    String followerReplay = followerWait(safeStage, safeRequestKey, policy, deadline);
                    if (followerReplay != null) {
                        return followerReplay;
                    }
                }
                default -> {
                    return localSingleFlightService.execute(safeRequestKey, supplier);
                }
            }
        }
        throw new CompletionException(new RejectedExecutionException("distributed single-flight max attempts exceeded"));
    }

    private String ownerExecute(String stage, String requestKey, Long ownerToken,
                                Supplier<String> supplier,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        long runningTtlMillis = positive(policy.getRunningTtlMillis(), 15000L);
        boolean markedRunning = flightCoordinatorRepository.markRunning(requestKey, nodeId(), ownerToken, runningTtlMillis);
        if (!markedRunning) {
            return followerWait(stage, requestKey, policy, System.currentTimeMillis() + resolveFollowerMaxWaitMillis());
        }

        FlightOwnerContext ownerContext = FlightOwnerContext.builder()
                .stage(stage)
                .requestKey(requestKey)
                .ownerId(nodeId())
                .ownerToken(ownerToken)
                .policy(policy)
                .build();
        String heartbeatTaskKey = flightHeartbeatManager.start(
                ownerContext,
                () -> flightCoordinatorRepository.heartbeat(requestKey, nodeId(), ownerToken, runningTtlMillis)
        );
        try {
            String result = supplier.get();
            FlightStoredResult storedResult = flightResultSerializer.serialize(result, ownerToken, policy);
            long resultTtlMillis = positive(policy.getResultTtlMillis(), 600000L);
            if (!flightCoordinatorRepository.storeResult(requestKey, nodeId(), ownerToken, storedResult, resultTtlMillis)) {
                throw new IllegalStateException("failed to store distributed flight result");
            }
            if (!flightCoordinatorRepository.finishSuccess(requestKey, nodeId(), ownerToken, resultTtlMillis)) {
                String replay = tryReadSuccessReplay(stage, requestKey, policy);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException("failed to finish distributed flight success state");
            }
            flightNotificationService.publish(requestKey, "owner_succeeded", FlightStatus.SUCCEEDED, ownerToken, null, false);
            flightReplayLocalCache.put(stage, requestKey, result, policy);
            return result;
        } catch (Throwable ex) {
            FlightFailure failure = classifyFailure(ex);
            flightCoordinatorRepository.finishFailure(
                    requestKey,
                    nodeId(),
                    ownerToken,
                    failure.errorType,
                    failure.errorCode,
                    failure.retryable,
                    positive(policy.getFailedResultTtlMillis(), 60000L)
            );
            flightNotificationService.publish(requestKey, "owner_failed", FlightStatus.FAILED, ownerToken, failure.errorType, failure.retryable);
            throw rethrow(ex);
        } finally {
            flightHeartbeatManager.stop(heartbeatTaskKey);
        }
    }

    private String followerWait(String stage, String requestKey,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy,
                                long deadlineMillis) {
        long streamBlockTimeoutMillis = positive(configuration.getStreamBlockTimeoutMillis(), 3000L);
        long pollIntervalMillis = positive(configuration.getPollFallbackIntervalMillis(), 2000L);
        long nextPollAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            String replay = tryReadSuccessReplay(stage, requestKey, policy);
            if (replay != null) {
                return replay;
            }
            FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
            if (metaSnapshot != null && metaSnapshot.getStatus() == FlightStatus.FAILED && !Boolean.TRUE.equals(metaSnapshot.getRetryable())) {
                throw new IllegalStateException("distributed single-flight previous failure: "
                        + (metaSnapshot.getErrorCode() == null ? "FAILED" : metaSnapshot.getErrorCode()));
            }
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return null;
            }
            flightNotificationService.waitForTerminalEvent(requestKey, Math.min(streamBlockTimeoutMillis, remainingMillis));
            if (System.currentTimeMillis() >= nextPollAt) {
                String polledReplay = tryReadSuccessReplay(stage, requestKey, policy);
                if (polledReplay != null) {
                    return polledReplay;
                }
                nextPollAt = System.currentTimeMillis() + pollIntervalMillis;
            }
        }
        return null;
    }

    private String tryReadSuccessReplay(String stage, String requestKey,
                                        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        String localReplay = flightReplayLocalCache.get(stage, requestKey);
        if (localReplay != null) {
            return localReplay;
        }
        FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
        if (metaSnapshot == null || metaSnapshot.getStatus() != FlightStatus.SUCCEEDED) {
            return null;
        }
        FlightStoredResult storedResult = flightCoordinatorRepository.getStoredResult(requestKey);
        if (storedResult == null) {
            return null;
        }
        String replay = flightResultSerializer.deserialize(storedResult);
        flightReplayLocalCache.put(stage, requestKey, replay, policy);
        return replay;
    }

    private RuntimeException replayFailure(FlightAcquireResult acquireResult) {
        boolean retryable = Boolean.TRUE.equals(acquireResult.getRetryable());
        String message = "distributed single-flight replay failure";
        if (StrUtil.isNotBlank(acquireResult.getErrorCode())) {
            message = message + ": " + acquireResult.getErrorCode();
        }
        if (retryable) {
            return new CompletionException(new RejectedExecutionException(message));
        }
        return new IllegalStateException(message);
    }

    private RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(throwable);
    }

    private FlightFailure classifyFailure(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof InterviewAiGuardException guardException) {
            return switch (guardException.getErrorCode()) {
                case AI_TIMEOUT -> new FlightFailure(FlightErrorType.TIMEOUT, guardException.getErrorCode().name(), true);
                case AI_OVERLOADED -> new FlightFailure(FlightErrorType.OVERLOAD, guardException.getErrorCode().name(), true);
                case AI_UNAVAILABLE -> new FlightFailure(FlightErrorType.PROVIDER, guardException.getErrorCode().name(), true);
            };
        }
        if (cause instanceof TimeoutException) {
            return new FlightFailure(FlightErrorType.TIMEOUT, "TIMEOUT", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return new FlightFailure(FlightErrorType.OVERLOAD, "OVERLOADED", true);
        }
        if (cause instanceof IllegalArgumentException) {
            return new FlightFailure(FlightErrorType.VALIDATION, "VALIDATION", false);
        }
        return new FlightFailure(FlightErrorType.UNEXPECTED, "UNEXPECTED", false);
    }

    private long resolveFollowerMaxWaitMillis() {
        return positive(configuration.getFollowerMaxWaitMillis(), 20000L);
    }

    private long positive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private String extractSessionId(String requestKey) {
        if (StrUtil.isBlank(requestKey)) {
            return null;
        }
        String[] parts = requestKey.split("\\|");
        return parts.length > 1 ? parts[1] : null;
    }

    private String nodeId() {
        return Holder.NODE_ID;
    }

    /**
     * 懒加载当前节点标识的内部工具类，用于生成 owner 节点身份。
     *
     * @author 程序员牛肉
     */
    private static final class Holder {
        private static final String NODE_ID = resolveNodeId();

        private static String resolveNodeId() {
            try {
                return InetAddress.getLocalHost().getHostName() + "@" + ManagementFactory.getRuntimeMXBean().getName();
            } catch (UnknownHostException ex) {
                return ManagementFactory.getRuntimeMXBean().getName();
            }
        }
    }

    /**
     * 分布式协调过程中对异常进行归类后的内部失败对象，
     * 用于统一写入失败状态并决定是否允许重试接管。
     *
     * @author 程序员牛肉
     */
    private record FlightFailure(FlightErrorType errorType, String errorCode, boolean retryable) {
    }
}
