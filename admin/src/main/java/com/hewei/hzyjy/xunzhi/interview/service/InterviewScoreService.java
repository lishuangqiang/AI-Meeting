package com.hewei.hzyjy.xunzhi.interview.service;

import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;

import java.util.List;

public interface InterviewScoreService {

    Integer getSessionTotalScore(String sessionId, List<InterviewTurnLog> turns);

    Integer addSessionScore(String sessionId, Integer score);

    void resetSessionScore(String sessionId);

    Integer getSessionDemeanorScore(String sessionId);

    DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId);
}

