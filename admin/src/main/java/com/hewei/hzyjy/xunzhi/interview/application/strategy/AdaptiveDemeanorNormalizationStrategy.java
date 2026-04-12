package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import org.springframework.stereotype.Component;

@Component
public class AdaptiveDemeanorNormalizationStrategy implements DemeanorNormalizationStrategy {

    @Override
    public boolean isLikelyTenScale(Integer... scores) {
        if (scores == null || scores.length == 0) {
            return false;
        }
        for (Integer score : scores) {
            if (score == null || score < 0 || score > 10) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int normalize(Integer score, boolean tenScaleDetected) {
        if (score == null) {
            return 0;
        }
        int normalized = tenScaleDetected ? score * 10 : score;
        return Math.max(0, Math.min(100, normalized));
    }
}

