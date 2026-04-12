package com.hewei.hzyjy.xunzhi.interview.application.flow;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class InterviewFlowStateMachine {

    private static final Map<InterviewFlowStatus, Set<InterviewFlowStatus>> LEGAL_TRANSITIONS = new EnumMap<>(InterviewFlowStatus.class);

    static {
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.INIT, EnumSet.of(InterviewFlowStatus.ASKING, InterviewFlowStatus.COMPLETED));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.ASKING, EnumSet.of(
                InterviewFlowStatus.EVALUATING,
                InterviewFlowStatus.FOLLOW_UP,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.EVALUATING, EnumSet.of(
                InterviewFlowStatus.ASKING,
                InterviewFlowStatus.FOLLOW_UP,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.FOLLOW_UP, EnumSet.of(
                InterviewFlowStatus.EVALUATING,
                InterviewFlowStatus.ASKING,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.COMPLETED, EnumSet.noneOf(InterviewFlowStatus.class));
    }

    private final InterviewQuestionCacheService interviewQuestionCacheService;

    public InterviewFlowState ensureInitialized(String sessionId, int totalQuestions) {
        if (StrUtil.isBlank(sessionId) || totalQuestions <= 0) {
            return null;
        }
        InterviewFlowState state = interviewQuestionCacheService.getInterviewFlow(sessionId);
        if (state != null) {
            return state;
        }
        interviewQuestionCacheService.initInterviewFlow(sessionId, totalQuestions);
        return interviewQuestionCacheService.getInterviewFlow(sessionId);
    }

    public InterviewFlowState current(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return interviewQuestionCacheService.getInterviewFlow(sessionId);
    }

    public boolean isCompleted(InterviewFlowState state) {
        return toStatus(state) == InterviewFlowStatus.COMPLETED;
    }

    public boolean isOutOfRange(InterviewFlowState state) {
        if (state == null) {
            return false;
        }
        Integer totalQuestions = state.getTotalQuestions();
        Integer currentIndex = state.getCurrentIndex();
        return totalQuestions != null && totalQuestions > 0
                && currentIndex != null && currentIndex >= totalQuestions;
    }

    public String currentQuestionNumber(InterviewFlowState state) {
        if (state == null || isOutOfRange(state)) {
            return null;
        }
        int currentIndex = state.getCurrentIndex() == null ? 0 : Math.max(0, state.getCurrentIndex());
        return String.valueOf(currentIndex + 1);
    }

    public InterviewFlowState moveToEvaluating(String sessionId) {
        return transitionStatus(sessionId, InterviewFlowStatus.EVALUATING);
    }

    public InterviewFlowState moveToFollowUp(String sessionId) {
        return transitionStatus(sessionId, InterviewFlowStatus.FOLLOW_UP);
    }

    public InterviewFlowState markCompleted(String sessionId) {
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null || isCompleted(currentState)) {
            return currentState;
        }
        if (!isLegalTransition(toStatus(currentState), InterviewFlowStatus.COMPLETED)) {
            throw illegalTransition(toStatus(currentState), InterviewFlowStatus.COMPLETED);
        }
        return interviewQuestionCacheService.markInterviewCompleted(sessionId);
    }

    public InterviewFlowState advanceMainQuestion(String sessionId) {
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null) {
            return null;
        }
        InterviewFlowStatus currentStatus = toStatus(currentState);
        if (currentStatus != InterviewFlowStatus.EVALUATING
                && currentStatus != InterviewFlowStatus.FOLLOW_UP
                && currentStatus != InterviewFlowStatus.ASKING) {
            throw illegalTransition(currentStatus, InterviewFlowStatus.ASKING);
        }

        InterviewFlowState next = interviewQuestionCacheService.advanceToNextQuestion(sessionId);
        if (next == null) {
            return null;
        }
        if (isOutOfRange(next)) {
            return markCompleted(sessionId);
        }
        return next;
    }

    private InterviewFlowState transitionStatus(String sessionId, InterviewFlowStatus targetStatus) {
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null || targetStatus == null) {
            return currentState;
        }

        InterviewFlowStatus sourceStatus = toStatus(currentState);
        if (sourceStatus == targetStatus) {
            return currentState;
        }
        if (!isLegalTransition(sourceStatus, targetStatus)) {
            throw illegalTransition(sourceStatus, targetStatus);
        }
        interviewQuestionCacheService.updateInterviewFlowStatus(sessionId, targetStatus.name());
        return current(sessionId);
    }

    private boolean isLegalTransition(InterviewFlowStatus sourceStatus, InterviewFlowStatus targetStatus) {
        if (sourceStatus == null || targetStatus == null) {
            return false;
        }
        Set<InterviewFlowStatus> allowed = LEGAL_TRANSITIONS.get(sourceStatus);
        return allowed != null && allowed.contains(targetStatus);
    }

    private InterviewFlowStatus toStatus(InterviewFlowState state) {
        return InterviewFlowStatus.from(state == null ? null : state.getStatus());
    }

    private IllegalStateException illegalTransition(InterviewFlowStatus sourceStatus, InterviewFlowStatus targetStatus) {
        return new IllegalStateException("illegal interview flow transition: " + sourceStatus + " -> " + targetStatus);
    }
}
