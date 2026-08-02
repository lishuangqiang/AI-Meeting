package com.hewei.hzyjy.xunzhi.interview.application.pipeline;

import com.hewei.hzyjy.xunzhi.interview.config.InterviewAnswerGuardConfiguration;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewAnswerIdempotencyService;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewAnswerIdempotencyServiceTest {

    @Test
    void shouldTransitFromNewToProcessingAndReplayCommittedRecord() {
        InterviewAnswerIdempotencyService service = newService();

        InterviewAnswerIdempotencyService.TryStartResult first = service.tryStart("s-1", "r-1", "1", "digest-1");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.NEW, first.getStatus());

        InterviewAnswerIdempotencyService.TryStartResult inFlight = service.tryStart("s-1", "r-1", "1", "digest-1");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.PROCESSING, inFlight.getStatus());

        // 原子提交写入 committedRecords 后，同 requestId 直接回放（不再依赖 replay key）。
        writeCommittedRecord("s-1", "r-1", "1", "digest-1", 85);

        InterviewAnswerIdempotencyService.TryStartResult replay = service.tryStart("s-1", "r-1", "1", "digest-1");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.SUCCEEDED, replay.getStatus());
        assertNotNull(replay.getReplayRecordJson());
        assertTrue(replay.getReplayRecordJson().contains("\"totalScore\":85"));
        assertTrue(replay.getReplayRecordJson().contains("\"nextQuestionNumber\":\"2\""));
    }

    @Test
    void shouldRejectFingerprintMismatch() {
        InterviewAnswerIdempotencyService service = newService();
        writeCommittedRecord("s-1", "r-1", "1", "digest-1", 85);

        InterviewAnswerIdempotencyService.TryStartResult mismatch = service.tryStart("s-1", "r-1", "1", "digest-OTHER");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.FINGERPRINT_MISMATCH, mismatch.getStatus());
    }

    @Test
    void shouldAllowRetryAfterClearProcessing() {
        InterviewAnswerIdempotencyService service = newService();

        InterviewAnswerIdempotencyService.TryStartResult first = service.tryStart("s-2", "r-2", "1", "digest-2");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.NEW, first.getStatus());

        InterviewAnswerIdempotencyService.TryStartResult inFlight = service.tryStart("s-2", "r-2", "1", "digest-2");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.PROCESSING, inFlight.getStatus());

        service.clearProcessing("s-2", "r-2");

        InterviewAnswerIdempotencyService.TryStartResult retry = service.tryStart("s-2", "r-2", "1", "digest-2");
        assertEquals(InterviewAnswerIdempotencyService.TryStartStatus.NEW, retry.getStatus());
    }

    private void writeCommittedRecord(String sessionId, String requestId,
                                      String questionNumber, String answerDigest, int totalScore) {
        String record = "{"
                + "\"questionNumber\":\"" + questionNumber + "\","
                + "\"answerDigest\":\"" + answerDigest + "\","
                + "\"flowVersion\":2,"
                + "\"currentQuestionNumber\":\"1\","
                + "\"status\":\"ASKING\","
                + "\"totalScore\":" + totalScore + ","
                + "\"score\":85,"
                + "\"feedback\":\"good\","
                + "\"nextQuestionNumber\":\"2\","
                + "\"nextQuestion\":\"question-2\","
                + "\"finished\":false,"
                + "\"isFollowUp\":false,"
                + "\"followUpCount\":0,"
                + "\"turnPayload\":{}"
                + "}";
        committedStore.put(InterviewCacheKeys.committedAnswer(sessionId) + ":" + requestId, record);
    }

    private final Map<String, String> committedStore = new ConcurrentHashMap<>();

    private InterviewAnswerIdempotencyService newService() {
        Map<String, String> redisStore = new ConcurrentHashMap<>();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> redisStore.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        doAnswer(invocation -> {
            redisStore.remove(invocation.getArgument(0));
            return Boolean.TRUE;
        }).when(stringRedisTemplate).delete(anyString());
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), any()))
                .thenAnswer(invocation -> committedStore.get(
                        invocation.getArgument(0) + ":" + invocation.getArgument(1)));

        InterviewAnswerGuardConfiguration configuration = new InterviewAnswerGuardConfiguration();
        configuration.setProcessingExpireSeconds(120L);
        return new InterviewAnswerIdempotencyService(stringRedisTemplate, configuration);
    }
}
