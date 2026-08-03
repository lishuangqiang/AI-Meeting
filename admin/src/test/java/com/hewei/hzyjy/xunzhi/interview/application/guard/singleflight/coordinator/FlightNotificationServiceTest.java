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
import org.springframework.data.redis.connection.stream.RecordId;
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
    void shouldReadFromAfterIdAndReturnRecordId() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1710000000000-0"));
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        // 首次 afterId="0"：迟到 follower 仍能立刻看到已存在的终态。
        String recordId = service.waitForTerminalEvent(REQUEST_KEY, "0", 50L);

        ArgumentCaptor<StreamOffset<String>> offsetCaptor = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streamOperations).read(any(StreamReadOptions.class), offsetCaptor.capture());
        ReadOffset readOffset = offsetCaptor.getValue().getOffset();
        assertEquals("0", readOffset.getOffset(), "首次应从流开头读取，而非 latest");
        assertEquals("1710000000000-0", recordId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAdvanceCursorWithPreviousRecordId() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1710000000001-0"));
        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        // 后续读取必须带上游游标，只收更新消息，避免旧事件反复唤醒。
        String recordId = service.waitForTerminalEvent(REQUEST_KEY, "1710000000000-0", 50L);

        ArgumentCaptor<StreamOffset<String>> offsetCaptor = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streamOperations).read(any(StreamReadOptions.class), offsetCaptor.capture());
        assertEquals("1710000000000-0", offsetCaptor.getValue().getOffset().getOffset());
        assertEquals("1710000000001-0", recordId);
    }

    @Test
    void shouldReturnNullWhenNoTerminalEvent() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        when(streamOperations.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        String recordId = service.waitForTerminalEvent(REQUEST_KEY, "0", 50L);

        assertNull(recordId);
    }

    @Test
    void shouldDeleteStreamKeyOnClear() {
        FlightNotificationService service = new FlightNotificationService(stringRedisTemplate);

        service.clearStream(REQUEST_KEY);

        verify(stringRedisTemplate).delete(STREAM_KEY);
    }
}
