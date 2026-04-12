package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AverageInterviewScoreAggregatorStrategyTest {

    private final AverageInterviewScoreAggregatorStrategy strategy = new AverageInterviewScoreAggregatorStrategy();

    @Test
    void shouldCalculateRoundedAverageFromTurns() {
        List<InterviewTurnLog> turns = List.of(
                InterviewTurnLog.builder().score(80).build(),
                InterviewTurnLog.builder().score(61).build(),
                InterviewTurnLog.builder().score(59).build()
        );

        Integer average = strategy.averageFromTurns(turns);

        assertEquals(67, average);
    }

    @Test
    void shouldIgnoreUnscoredTurns() {
        List<InterviewTurnLog> turns = List.of(
                InterviewTurnLog.builder().score(null).build(),
                InterviewTurnLog.builder().build()
        );

        assertNull(strategy.averageFromTurns(turns));
    }

    @Test
    void shouldClampOutOfRangeAverage() {
        assertEquals(100, strategy.averageFromAggregate(1200L, 10L));
        assertEquals(0, strategy.averageFromAggregate(-120L, 10L));
    }
}
