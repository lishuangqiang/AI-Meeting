package com.hewei.hzyjy.xunzhi.interview.application.strategy;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import org.springframework.stereotype.Component;

@Component
public class WeightedRadarComputationStrategy implements RadarComputationStrategy {

    private static final double RESUME_WEIGHT = 0.25D;
    private static final double INTERVIEW_WEIGHT = 0.55D;
    private static final double DEMEANOR_WEIGHT = 0.20D;
    private static final double PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT = 0.70D;
    private static final double PROFESSIONAL_SKILLS_RESUME_WEIGHT = 0.30D;

    @Override
    public RadarChartDTO compute(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        Integer resume = clampNullableScore(resumeScore);
        Integer interview = clampNullableScore(interviewScore);
        Integer demeanor = clampNullableScore(demeanorScore);

        RadarChartDTO radarChart = new RadarChartDTO();
        radarChart.setResumeScore(defaultScore(resume));
        radarChart.setInterviewPerformance(defaultScore(interview));
        radarChart.setDemeanorEvaluation(defaultScore(demeanor));
        radarChart.setProfessionalSkills(calculateProfessionalSkills(resume, interview));
        radarChart.setPotentialIndex(calculateWeightedComposite(resume, interview, demeanor));
        return radarChart;
    }

    private int calculateProfessionalSkills(Integer resumeScore, Integer interviewScore) {
        double weightedScore = 0D;
        double totalWeight = 0D;
        if (resumeScore != null) {
            weightedScore += clampScore(resumeScore) * PROFESSIONAL_SKILLS_RESUME_WEIGHT;
            totalWeight += PROFESSIONAL_SKILLS_RESUME_WEIGHT;
        }
        if (interviewScore != null) {
            weightedScore += clampScore(interviewScore) * PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT;
            totalWeight += PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT;
        }
        if (totalWeight <= 0D) {
            return 0;
        }
        return clampScore((int) Math.round(weightedScore / totalWeight));
    }

    private int calculateWeightedComposite(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        double weightedScore = 0D;
        double totalWeight = 0D;
        if (resumeScore != null) {
            weightedScore += clampScore(resumeScore) * RESUME_WEIGHT;
            totalWeight += RESUME_WEIGHT;
        }
        if (interviewScore != null) {
            weightedScore += clampScore(interviewScore) * INTERVIEW_WEIGHT;
            totalWeight += INTERVIEW_WEIGHT;
        }
        if (demeanorScore != null) {
            weightedScore += clampScore(demeanorScore) * DEMEANOR_WEIGHT;
            totalWeight += DEMEANOR_WEIGHT;
        }
        if (totalWeight <= 0D) {
            return 0;
        }
        return clampScore((int) Math.round(weightedScore / totalWeight));
    }

    private int defaultScore(Integer score) {
        return score == null ? 0 : clampScore(score);
    }

    private Integer clampNullableScore(Integer score) {
        if (score == null) {
            return null;
        }
        return clampScore(score);
    }

    private int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }
}

