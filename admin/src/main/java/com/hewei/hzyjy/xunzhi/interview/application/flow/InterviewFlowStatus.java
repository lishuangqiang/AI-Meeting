package com.hewei.hzyjy.xunzhi.interview.application.flow;

public enum InterviewFlowStatus {
    INIT,
    ASKING,
    EVALUATING,
    FOLLOW_UP,
    COMPLETED;

    public static InterviewFlowStatus from(String rawStatus) {
        if (rawStatus == null) {
            return INIT;
        }
        try {
            return InterviewFlowStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (Exception ex) {
            return INIT;
        }
    }
}
