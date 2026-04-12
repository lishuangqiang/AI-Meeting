package com.hewei.hzyjy.xunzhi.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.DemeanorNormalizationStrategy;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.InterviewScoreAggregatorStrategy;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewScoreService;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheStore;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InterviewScoreServiceImpl implements InterviewScoreService {

    private static final long CACHE_EXPIRE_HOURS = 24L;

    private final InterviewCacheStore interviewCacheStore;
    private final InterviewScoreAggregatorStrategy scoreAggregatorStrategy;
    private final DemeanorNormalizationStrategy demeanorNormalizationStrategy;

    @Override
    public Integer getSessionTotalScore(String sessionId, List<InterviewTurnLog> turns) {
        if (StrUtil.isBlank(sessionId)) {
            return 0;
        }

        Integer cachedAverage = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.sessionScore(sessionId)));
        if (cachedAverage != null) {
            return scoreAggregatorStrategy.clampScore(cachedAverage);
        }

        Integer derivedAverage = scoreAggregatorStrategy.averageFromTurns(turns);
        return derivedAverage != null ? derivedAverage : 0;
    }

    @Override
    public Integer addSessionScore(String sessionId, Integer score) {
        if (StrUtil.isBlank(sessionId)) {
            return 0;
        }

        int safeScore = scoreAggregatorStrategy.clampScore(score == null ? 0 : score);
        Long scoreSum = interviewCacheStore.increment(InterviewCacheKeys.sessionScoreSum(sessionId), safeScore);
        Long answerCount = interviewCacheStore.increment(InterviewCacheKeys.sessionScoreCount(sessionId), 1L);
        int averagedScore = scoreAggregatorStrategy.averageFromAggregate(scoreSum, answerCount);

        interviewCacheStore.setValue(
                InterviewCacheKeys.sessionScore(sessionId),
                String.valueOf(averagedScore),
                CACHE_EXPIRE_HOURS
        );
        interviewCacheStore.expire(InterviewCacheKeys.sessionScoreSum(sessionId), CACHE_EXPIRE_HOURS);
        interviewCacheStore.expire(InterviewCacheKeys.sessionScoreCount(sessionId), CACHE_EXPIRE_HOURS);
        return averagedScore;
    }

    @Override
    public void resetSessionScore(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        interviewCacheStore.deleteKeys(List.of(
                InterviewCacheKeys.sessionScore(sessionId),
                InterviewCacheKeys.sessionScoreSum(sessionId),
                InterviewCacheKeys.sessionScoreCount(sessionId)
        ));
    }

    @Override
    public Integer getSessionDemeanorScore(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }

        Integer rawScore = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorScore(sessionId)));
        if (rawScore == null) {
            return null;
        }

        Integer panic = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorPanic(sessionId)));
        Integer seriousness = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorSeriousness(sessionId)));
        Integer emoticon = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorEmoticon(sessionId)));
        boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(panic, seriousness, emoticon, rawScore);
        return demeanorNormalizationStrategy.normalize(rawScore, tenScaleDetected);
    }

    @Override
    public DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId) {
        DemeanorScoreDTO detail = new DemeanorScoreDTO();
        if (StrUtil.isBlank(sessionId)) {
            return detail;
        }

        Integer panicLevel = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorPanic(sessionId)));
        Integer seriousnessLevel = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorSeriousness(sessionId)));
        Integer emoticonHandling = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorEmoticon(sessionId)));
        Integer compositeScore = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorComposite(sessionId)));
        boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(
                panicLevel,
                seriousnessLevel,
                emoticonHandling,
                compositeScore
        );

        detail.setPanicLevel(demeanorNormalizationStrategy.normalize(panicLevel, tenScaleDetected));
        detail.setSeriousnessLevel(demeanorNormalizationStrategy.normalize(seriousnessLevel, tenScaleDetected));
        detail.setEmoticonHandling(demeanorNormalizationStrategy.normalize(emoticonHandling, tenScaleDetected));
        detail.setCompositeScore(demeanorNormalizationStrategy.normalize(compositeScore, tenScaleDetected));
        return detail;
    }

    private Integer parseInteger(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
