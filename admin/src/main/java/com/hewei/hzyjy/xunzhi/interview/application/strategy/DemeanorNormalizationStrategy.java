package com.hewei.hzyjy.xunzhi.interview.application.strategy;

public interface DemeanorNormalizationStrategy {

    boolean isLikelyTenScale(Integer... scores);

    int normalize(Integer score, boolean tenScaleDetected);
}

