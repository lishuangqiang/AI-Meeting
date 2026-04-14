package com.hewei.hzyjy.xunzhi.interview.service.impl;

import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRadarService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewScoreService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewTurnPersistenceTest {

    @Test
    void shouldAppendTurnOnceByRequestId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForList()).thenReturn(listOps);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Boolean.TRUE);
        when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(1L);

        InterviewQuestionCacheServiceImpl service = new InterviewQuestionCacheServiceImpl(
                redis,
                mock(InterviewQuestionService.class),
                mock(InterviewScoreService.class),
                mock(InterviewRadarService.class)
        );

        InterviewTurnLog turn = InterviewTurnLog.builder().requestId("r-1").questionNumber("1").build();
        assertTrue(service.appendInterviewTurnIfAbsent("s-1", turn));
    }

    @Test
    void shouldTreatDuplicateTurnRequestAsSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForList()).thenReturn(listOps);
        when(setOps.add(anyString(), anyString())).thenReturn(0L);
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Boolean.TRUE);

        InterviewQuestionCacheServiceImpl service = new InterviewQuestionCacheServiceImpl(
                redis,
                mock(InterviewQuestionService.class),
                mock(InterviewScoreService.class),
                mock(InterviewRadarService.class)
        );

        InterviewTurnLog turn = InterviewTurnLog.builder().requestId("r-dup").questionNumber("1").build();
        assertTrue(service.appendInterviewTurnIfAbsent("s-1", turn));
        verify(listOps, never()).rightPush(anyString(), anyString());
    }

    @Test
    void shouldRollbackRequestIdWhenTurnAppendFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForList()).thenReturn(listOps);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Boolean.TRUE);
        doThrow(new RuntimeException("boom")).when(listOps).rightPush(anyString(), anyString());

        InterviewQuestionCacheServiceImpl service = new InterviewQuestionCacheServiceImpl(
                redis,
                mock(InterviewQuestionService.class),
                mock(InterviewScoreService.class),
                mock(InterviewRadarService.class)
        );

        InterviewTurnLog turn = InterviewTurnLog.builder().requestId("r-fail").questionNumber("1").build();
        assertFalse(service.appendInterviewTurnIfAbsent("s-1", turn));
        verify(setOps).remove(anyString(), anyString());
    }
}
