package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveDemeanorNormalizationStrategyTest {

    private final AdaptiveDemeanorNormalizationStrategy strategy = new AdaptiveDemeanorNormalizationStrategy();

    @Test
    void shouldDetectTenPointScaleWhenAllScoresWithinRange() {
        assertTrue(strategy.isLikelyTenScale(8, 10, 7, 9));
    }

    @Test
    void shouldRejectTenPointScaleWhenAnyScoreOutOfRange() {
        assertFalse(strategy.isLikelyTenScale(8, 10, 11, 9));
    }

    @Test
    void shouldNormalizeTenPointScaleToHundredPointScale() {
        assertEquals(90, strategy.normalize(9, true));
    }

    @Test
    void shouldClampHundredScaleScore() {
        assertEquals(100, strategy.normalize(120, false));
        assertEquals(0, strategy.normalize(-12, false));
    }
}
