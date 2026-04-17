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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class InterviewScoreServiceImpl implements InterviewScoreService {

    private static final long CACHE_EXPIRE_HOURS = 24L;
    // 用 Lua 一次完成 sum/count/avg 更新，降低多 key 分步写入造成的数据漂移窗口。
    private static final String SCORE_AGGREGATE_UPDATE_SCRIPT_TEXT =
            "local sum = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1])) "
                    + "local cnt = redis.call('INCRBY', KEYS[2], 1) "
                    + "local avg = math.floor((sum / cnt) + 0.5) "
                    + "redis.call('SET', KEYS[3], tostring(avg)) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "
                    + "redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2])) "
                    + "redis.call('EXPIRE', KEYS[3], tonumber(ARGV[2])) "
                    + "return {sum, cnt, avg}";
    private static final DefaultRedisScript<List> SCORE_AGGREGATE_UPDATE_SCRIPT = initScoreAggregateScript();

    private final InterviewCacheStore interviewCacheStore;
    private final StringRedisTemplate stringRedisTemplate;
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
        String scoreSumKey = InterviewCacheKeys.sessionScoreSum(sessionId);
        String scoreCountKey = InterviewCacheKeys.sessionScoreCount(sessionId);
        String scoreKey = InterviewCacheKeys.sessionScore(sessionId);
        long ttlSeconds = TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS);

        try {
            // 主路径：原子更新聚合分数。
            List<?> scriptResult = stringRedisTemplate.execute(
                    SCORE_AGGREGATE_UPDATE_SCRIPT,
                    List.of(scoreSumKey, scoreCountKey, scoreKey),
                    String.valueOf(safeScore),
                    String.valueOf(ttlSeconds)
            );
            Integer averageFromScript = parseAverageFromScriptResult(scriptResult);
            if (averageFromScript != null) {
                return scoreAggregatorStrategy.clampScore(averageFromScript);
            }
        } catch (Exception ignored) {
            // 兼容路径：脚本不可用时退化到旧逻辑，优先保证可用性。
        }

        // 旧逻辑兜底：不是强原子，但可在脚本异常时继续服务。
        Long scoreSum = interviewCacheStore.increment(scoreSumKey, safeScore);
        Long answerCount = interviewCacheStore.increment(scoreCountKey, 1L);
        int averagedScore = scoreAggregatorStrategy.averageFromAggregate(scoreSum, answerCount);
        interviewCacheStore.setValue(scoreKey, String.valueOf(averagedScore), CACHE_EXPIRE_HOURS);
        interviewCacheStore.expire(scoreSumKey, CACHE_EXPIRE_HOURS);
        interviewCacheStore.expire(scoreCountKey, CACHE_EXPIRE_HOURS);
        return scoreAggregatorStrategy.clampScore(averagedScore);
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

    private Integer parseAverageFromScriptResult(List<?> scriptResult) {
        if (scriptResult == null || scriptResult.size() < 3) {
            return null;
        }
        Object avg = scriptResult.get(2);
        if (avg == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(avg));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DefaultRedisScript<List> initScoreAggregateScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(SCORE_AGGREGATE_UPDATE_SCRIPT_TEXT);
        script.setResultType(List.class);
        return script;
    }
}
