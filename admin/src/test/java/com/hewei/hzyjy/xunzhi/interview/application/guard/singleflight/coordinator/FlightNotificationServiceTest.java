package com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.coordinator;

import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightErrorType;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.model.FlightStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightNotificationServiceTest {

    private static final String REQUEST_KEY = "interview-evaluation|s1|1|a1";
    private static final String STREAM_KEY = "ai:flight:stream:" + REQUEST_KEY;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Test
    void shouldExpireStreamAfterPublishingSuccess() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        service.publish(REQUEST_KEY, "owner_succeeded", FlightStatus.SUCCEEDED, 1L, null, false, 600000L);

        verify(streamOperations).add(any());
        // 终态写入后必须按 TTL 回收通知流，避免随请求量无限增长。
        verify(stringRedisTemplate).expire(eq(STREAM_KEY), eq(Duration.ofMillis(600000L)));
    }

    @Test
    void shouldExpireStreamAfterPublishingFailure() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        service.publish(REQUEST_KEY, "owner_failed", FlightStatus.FAILED, 1L,
                FlightErrorType.TIMEOUT, true, 60000L);

        verify(streamOperations).add(any());
        verify(stringRedisTemplate).expire(eq(STREAM_KEY), eq(Duration.ofMillis(60000L)));
    }

    @Test
    void shouldClampTooSmallTtlToMinimum() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        // 上游传入非正数时，必须兜底为最小下限，避免 Redis 拒绝设置过期时间。
        service.publish(REQUEST_KEY, "owner_succeeded", FlightStatus.SUCCEEDED, 1L, null, false, 0L);

        verify(stringRedisTemplate).expire(eq(STREAM_KEY), eq(Duration.ofMillis(1000L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReadFromStreamHeadSoLateFollowerCanWakeImmediately() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(java.util.Map.of("status", "SUCCEEDED"));
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        FlightStatus status = service.waitForTerminalEvent(REQUEST_KEY, 50L);

        // 迟到 follower 从流开头读，应能立刻看到已存在的终态事件被唤醒。
        ArgumentCaptor<StreamOffset<String>> offsetCaptor = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streamOperations).read(any(StreamReadOptions.class), offsetCaptor.capture());
        ReadOffset readOffset = offsetCaptor.getValue().getOffset();
        assertEquals("0", readOffset.getOffset(), "通知流应从开头读取，而非 latest");
        assertEquals(FlightStatus.SUCCEEDED, status);
    }

    @Test
    void shouldReturnNullWhenNoTerminalEvent() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        FlightStatus status = service.waitForTerminalEvent(REQUEST_KEY, 50L);

        assertNull(status);
    }
}
