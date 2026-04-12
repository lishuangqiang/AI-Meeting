package com.hewei.hzyjy.xunzhi.interview.service;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;

public interface InterviewRadarService {

    RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

