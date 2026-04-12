package com.hewei.hzyjy.xunzhi.interview.service.impl;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.RadarComputationStrategy;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InterviewRadarServiceImpl implements InterviewRadarService {

    private final RadarComputationStrategy radarComputationStrategy;

    @Override
    public RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        try {
            return radarComputationStrategy.compute(resumeScore, interviewScore, demeanorScore);
        } catch (Exception ex) {
            RadarChartDTO defaultChart = new RadarChartDTO();
            defaultChart.setResumeScore(0);
            defaultChart.setInterviewPerformance(0);
            defaultChart.setDemeanorEvaluation(0);
            defaultChart.setProfessionalSkills(0);
            defaultChart.setPotentialIndex(0);
            return defaultChart;
        }
    }
}
