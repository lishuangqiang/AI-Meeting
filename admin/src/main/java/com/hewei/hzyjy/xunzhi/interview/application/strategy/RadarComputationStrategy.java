package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;

public interface RadarComputationStrategy {

    RadarChartDTO compute(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

