package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightErrorType;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布式 flight 的通知服务，负责把 owner 的终态结果写入 Redis Stream，
 * 并为 follower 提供阻塞等待通知的能力。
 *
 * @author 程序员牛肉
 */
@Service
@RequiredArgsConstructor
public class FlightNotificationService {

    private static final String STREAM_KEY_PREFIX = "ai:flight:stream:";

    private final StringRedisTemplate stringRedisTemplate;

    public void publish(String requestKey, String eventType, FlightStatus status, Long ownerToken,
                        FlightErrorType errorType, boolean retryable) {
        Map<String, String> body = new HashMap<>();
        body.put("eventType", StrUtil.blankToDefault(eventType, "updated"));
        body.put("status", status == null ? "" : status.name());
        body.put("ownerToken", ownerToken == null ? "" : String.valueOf(ownerToken));
        body.put("errorType", errorType == null ? "" : errorType.name());
        body.put("retryable", retryable ? "1" : "0");
        body.put("finishedAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(streamKey(requestKey)));
    }

    public FlightStatus waitForTerminalEvent(String requestKey, long blockTimeoutMillis) {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(Math.max(1L, blockTimeoutMillis))),
                StreamOffset.create(streamKey(requestKey), ReadOffset.latest())
        );
        if (records == null || records.isEmpty()) {
            return null;
        }
        Object status = records.get(0).getValue().get("status");
        return FlightStatus.from(status == null ? null : status.toString());
    }

    private String streamKey(String requestKey) {
        return STREAM_KEY_PREFIX + requestKey;
    }
}
