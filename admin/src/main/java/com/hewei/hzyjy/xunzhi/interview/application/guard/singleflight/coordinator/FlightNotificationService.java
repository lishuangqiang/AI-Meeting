package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightErrorType;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
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

    /**
     * 通知流 TTL 的最小下限，避免上游传入过小或非正数导致 Redis 拒绝设置过期时间。
     */
    private static final long MIN_STREAM_TTL_MILLIS = 1000L;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 将 owner 的终态事件写入 Redis Stream，并按 TTL 回收通知流。
     * <p>
     * 通知流仅作为 follower 的唤醒信号，真实终态以 meta/result 的 Redis Hash 为准。
     * 终态写入后立即设置过期时间，避免每次请求留下一把永不过期的 stream key 造成内存泄漏。
     *
     * @param requestKey      single-flight 请求指纹
     * @param eventType       事件类型，例如 owner_succeeded / owner_failed
     * @param status          终态状态
     * @param ownerToken      owner 令牌
     * @param errorType       失败时的错误类型，成功时为 null
     * @param retryable       失败是否可重试
     * @param streamTtlMillis 通知流存活时长，应不少于 follower 最长等待时间，避免提前回收
     */
    public void publish(String requestKey, String eventType, FlightStatus status, Long ownerToken,
                        FlightErrorType errorType, boolean retryable, long streamTtlMillis) {
        Map<String, String> body = new HashMap<>();
        body.put("eventType", StrUtil.blankToDefault(eventType, "updated"));
        body.put("status", status == null ? "" : status.name());
        body.put("ownerToken", ownerToken == null ? "" : String.valueOf(ownerToken));
        body.put("errorType", errorType == null ? "" : errorType.name());
        body.put("retryable", retryable ? "1" : "0");
        body.put("finishedAt", String.valueOf(System.currentTimeMillis()));
        String key = streamKey(requestKey);
        stringRedisTemplate.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(key));
        // 终态写入后按 TTL 回收通知流，避免随请求量无限增长；TTL 与 meta/result key 保持一致量级。
        long safeTtlMillis = Math.max(MIN_STREAM_TTL_MILLIS, streamTtlMillis);
        stringRedisTemplate.expire(key, Duration.ofMillis(safeTtlMillis));
    }

    /**
     * 按 Redis Stream 游标阻塞读取下一条通知。
     * <p>
     * 首次调用应传 {@code afterId="0"}，迟到 follower 仍能立刻看到已存在的终态；
     * 读到记录后应把返回的 record id 作为下次 {@code afterId}，只收更新的消息，
     * 避免同一条旧事件反复唤醒导致忙等。
     *
     * @param requestKey         single-flight 请求指纹
     * @param afterId            排他下界游标，首次传 {@code "0"}
     * @param blockTimeoutMillis XREAD BLOCK 超时毫秒
     * @return 读到的 stream record id；超时或无消息时返回 null
     */
    public String waitForTerminalEvent(String requestKey, String afterId, long blockTimeoutMillis) {
        String offset = StrUtil.blankToDefault(afterId, "0");
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(Math.max(1L, blockTimeoutMillis))),
                StreamOffset.create(streamKey(requestKey), ReadOffset.from(offset))
        );
        if (records == null || records.isEmpty()) {
            return null;
        }
        RecordId recordId = records.get(0).getId();
        return recordId == null ? null : recordId.getValue();
    }

    /**
     * OWNER_TAKEOVER 换代时删除旧通知流，避免跨代残留事件继续吵醒 follower。
     */
    public void clearStream(String requestKey) {
        stringRedisTemplate.delete(streamKey(requestKey));
    }

    private String streamKey(String requestKey) {
        return STREAM_KEY_PREFIX + requestKey;
    }
}
