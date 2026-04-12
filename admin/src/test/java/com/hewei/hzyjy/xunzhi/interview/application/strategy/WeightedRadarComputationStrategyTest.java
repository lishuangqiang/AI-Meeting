package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeightedRadarComputationStrategyTest {

    private final WeightedRadarComputationStrategy strategy = new WeightedRadarComputationStrategy();

    @Test
    void shouldComputeWeightedRadarScores() {
        RadarChartDTO chart = strategy.compute(80, 70, 90);

        assertEquals(80, chart.getResumeScore());
        assertEquals(70, chart.getInterviewPerformance());
        assertEquals(90, chart.getDemeanorEvaluation());
        assertEquals(73, chart.getProfessionalSkills());
        assertEquals(77, chart.getPotentialIndex());
    }

    @Test
    void shouldReturnZeroWhenAllDimensionsMissing() {
        RadarChartDTO chart = strategy.compute(null, null, null);

        assertEquals(0, chart.getResumeScore());
        assertEquals(0, chart.getInterviewPerformance());
        assertEquals(0, chart.getDemeanorEvaluation());
        assertEquals(0, chart.getProfessionalSkills());
        assertEquals(0, chart.getPotentialIndex());
    }
}
