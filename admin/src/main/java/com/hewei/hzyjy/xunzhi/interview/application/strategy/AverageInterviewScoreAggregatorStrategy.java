package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AverageInterviewScoreAggregatorStrategy implements InterviewScoreAggregatorStrategy {

    @Override
    public int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }

    @Override
    public int averageFromAggregate(Long scoreSum, Long answerCount) {
        if (scoreSum == null || answerCount == null || answerCount <= 0) {
            return 0;
        }
        return clampScore((int) Math.round((double) scoreSum / answerCount));
    }

    @Override
    public Integer averageFromTurns(List<InterviewTurnLog> turns) {
        if (turns == null || turns.isEmpty()) {
            return null;
        }

        int scoreSum = 0;
        int scoredTurns = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null) {
                continue;
            }
            scoreSum += clampScore(turn.getScore());
            scoredTurns++;
        }

        if (scoredTurns <= 0) {
            return null;
        }
        return clampScore((int) Math.round((double) scoreSum / scoredTurns));
    }
}

