package com.hewei.hzyjy.xunzhi.interview.service.impl;

import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRadarService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewQuestionCacheServiceImplTest {

    @Test
    void shouldUseRollingAverageForInterviewScore() {
        InterviewScoreService scoreService = mock(InterviewScoreService.class);
        when(scoreService.addSessionScore("session-1", 80)).thenReturn(80);
        when(scoreService.addSessionScore("session-1", 60)).thenReturn(70);
        when(scoreService.getSessionTotalScore(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(70);
        InterviewQuestionCacheServiceImpl service = createService(new ConcurrentHashMap<>(), scoreService, mock(InterviewRadarService.class));

        assertEquals(80, service.addSessionScore("session-1", 80));
        assertEquals(70, service.addSessionScore("session-1", 60));
        assertEquals(70, service.getSessionTotalScore("session-1"));
    }

    @Test
    void shouldBuildDeterministicWeightedRadarChart() {
        InterviewScoreService scoreService = mock(InterviewScoreService.class);
        when(scoreService.getSessionTotalScore(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(70);
        when(scoreService.getSessionDemeanorScore("session-2")).thenReturn(90);

        InterviewRadarService radarService = mock(InterviewRadarService.class);
        RadarChartDTO expectedChart = new RadarChartDTO();
        expectedChart.setResumeScore(80);
        expectedChart.setInterviewPerformance(70);
        expectedChart.setDemeanorEvaluation(90);
        expectedChart.setProfessionalSkills(73);
        expectedChart.setPotentialIndex(77);
        when(radarService.buildRadarChart(80, 70, 90)).thenReturn(expectedChart);

        InterviewQuestionCacheServiceImpl service = createService(new ConcurrentHashMap<>(), scoreService, radarService);

        service.cacheResumeScore("session-2", 80);

        RadarChartDTO radarChart = service.getRadarChartData("session-2");

        assertEquals(80, radarChart.getResumeScore());
        assertEquals(70, radarChart.getInterviewPerformance());
        assertEquals(90, radarChart.getDemeanorEvaluation());
        assertEquals(73, radarChart.getProfessionalSkills());
        assertEquals(77, radarChart.getPotentialIndex());
        verify(radarService).buildRadarChart(80, 70, 90);
    }

    @Test
    void shouldKeepDemeanorDetailsInHundredPointScale() {
        InterviewScoreService scoreService = mock(InterviewScoreService.class);
        DemeanorScoreDTO expected = new DemeanorScoreDTO();
        expected.setPanicLevel(91);
        expected.setSeriousnessLevel(84);
        expected.setEmoticonHandling(77);
        expected.setCompositeScore(88);
        when(scoreService.getSessionDemeanorScoreDetails("session-3")).thenReturn(expected);

        InterviewQuestionCacheServiceImpl service = createService(new ConcurrentHashMap<>(), scoreService, mock(InterviewRadarService.class));

        DemeanorScoreDTO details = service.getSessionDemeanorScoreDetails("session-3");

        assertEquals(91, details.getPanicLevel());
        assertEquals(84, details.getSeriousnessLevel());
        assertEquals(77, details.getEmoticonHandling());
        assertEquals(88, details.getCompositeScore());
    }

    private InterviewQuestionCacheServiceImpl createService(
            Map<String, String> redisStore,
            InterviewScoreService scoreService,
            InterviewRadarService radarService) {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        InterviewQuestionService interviewQuestionService = mock(InterviewQuestionService.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.expire(anyString(), anyLong(), org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        when(valueOperations.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Long delta = invocation.getArgument(1);
            long current = redisStore.containsKey(key) ? Long.parseLong(redisStore.get(key)) : 0L;
            long updated = current + delta;
            redisStore.put(key, String.valueOf(updated));
            return updated;
        });
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString());
        doAnswer(invocation -> {
            redisStore.remove(invocation.getArgument(0));
            return Boolean.TRUE;
        }).when(stringRedisTemplate).delete(anyString());
        doAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            keys.forEach(redisStore::remove);
            return Long.valueOf(keys.size());
        }).when(stringRedisTemplate).delete(anyCollection());

        return new InterviewQuestionCacheServiceImpl(stringRedisTemplate, interviewQuestionService, scoreService, radarService);
    }
}
