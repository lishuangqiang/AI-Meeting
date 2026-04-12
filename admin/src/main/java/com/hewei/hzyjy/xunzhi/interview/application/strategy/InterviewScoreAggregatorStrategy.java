package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;

import java.util.List;

public interface InterviewScoreAggregatorStrategy {

    int clampScore(Integer score);

    int averageFromAggregate(Long scoreSum, Long answerCount);

    Integer averageFromTurns(List<InterviewTurnLog> turns);
}

