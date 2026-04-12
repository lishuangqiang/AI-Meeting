package com.hewei.hzyjy.xunzhi.interview.service.impl;

import com.hewei.hzyjy.xunzhi.interview.application.strategy.AdaptiveDemeanorNormalizationStrategy;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.AverageInterviewScoreAggregatorStrategy;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheStore;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewScoreServiceImplTest {

    @Test
    void shouldUseWeightedAverageFromAggregateCounters() {
        InterviewCacheStore cacheStore = mock(InterviewCacheStore.class);
        InterviewScoreServiceImpl service = new InterviewScoreServiceImpl(
                cacheStore,
                new AverageInterviewScoreAggregatorStrategy(),
                new AdaptiveDemeanorNormalizationStrategy()
        );

        when(cacheStore.increment(InterviewCacheKeys.sessionScoreSum("s-1"), 80L)).thenReturn(140L);
        when(cacheStore.increment(InterviewCacheKeys.sessionScoreCount("s-1"), 1L)).thenReturn(2L);

        Integer score = service.addSessionScore("s-1", 80);

        assertEquals(70, score);
        verify(cacheStore).setValue(InterviewCacheKeys.sessionScore("s-1"), "70", 24L);
    }

    @Test
    void shouldNormalizeTenScaleDemeanorScoreToHundredScale() {
        InterviewCacheStore cacheStore = mock(InterviewCacheStore.class);
        InterviewScoreServiceImpl service = new InterviewScoreServiceImpl(
                cacheStore,
                new AverageInterviewScoreAggregatorStrategy(),
                new AdaptiveDemeanorNormalizationStrategy()
        );

        when(cacheStore.getValue(InterviewCacheKeys.demeanorScore("s-2"))).thenReturn("8");
        when(cacheStore.getValue(InterviewCacheKeys.demeanorPanic("s-2"))).thenReturn("9");
        when(cacheStore.getValue(InterviewCacheKeys.demeanorSeriousness("s-2"))).thenReturn("8");
        when(cacheStore.getValue(InterviewCacheKeys.demeanorEmoticon("s-2"))).thenReturn("7");

        Integer demeanorScore = service.getSessionDemeanorScore("s-2");

        assertEquals(80, demeanorScore);
    }

    @Test
    void shouldFallbackToTurnAverageWhenCacheMissing() {
        InterviewCacheStore cacheStore = mock(InterviewCacheStore.class);
        InterviewScoreServiceImpl service = new InterviewScoreServiceImpl(
                cacheStore,
                new AverageInterviewScoreAggregatorStrategy(),
                new AdaptiveDemeanorNormalizationStrategy()
        );

        when(cacheStore.getValue(InterviewCacheKeys.sessionScore("s-3"))).thenReturn(null);

        Integer totalScore = service.getSessionTotalScore("s-3", List.of(
                InterviewTurnLog.builder().score(80).build(),
                InterviewTurnLog.builder().score(60).build()
        ));

        assertEquals(70, totalScore);
    }
}
