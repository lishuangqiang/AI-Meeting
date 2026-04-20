package com.hewei.hzyjy.xunzhi.interview.application.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * owner 节点的 heartbeat 调度器，负责定时执行续租动作，
 * 保证长耗时 AI 请求在运行期间不会因为 TTL 到期而被误接管。
 *
 * @author 程序员牛肉
 */
@Service
@RequiredArgsConstructor
public class FlightHeartbeatManager {

    @Qualifier("scheduledExecutorService")
    private final ScheduledExecutorService scheduledExecutorService;

    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public String start(FlightOwnerContext ownerContext, BooleanSupplier heartbeatAction) {
        long intervalMillis = ownerContext.getPolicy() == null || ownerContext.getPolicy().getHeartbeatIntervalMillis() == null
                ? 3000L
                : Math.max(500L, ownerContext.getPolicy().getHeartbeatIntervalMillis());
        String taskKey = ownerContext.getRequestKey() + "|" + ownerContext.getOwnerToken();
        ScheduledFuture<?> future = scheduledExecutorService.scheduleAtFixedRate(
                () -> heartbeatAction.getAsBoolean(),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        futures.put(taskKey, future);
        return taskKey;
    }

    public void stop(String taskKey) {
        if (taskKey == null) {
            return;
        }
        ScheduledFuture<?> future = futures.remove(taskKey);
        if (future != null) {
            future.cancel(true);
        }
    }
}
