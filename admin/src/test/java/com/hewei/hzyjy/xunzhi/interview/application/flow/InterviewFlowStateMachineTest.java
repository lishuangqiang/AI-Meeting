package com.hewei.hzyjy.xunzhi.interview.application.flow;

import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewFlowStateMachineTest {

    @Test
    void shouldInitializeFlowWhenMissing() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = new InterviewFlowStateMachine(cacheService);
        InterviewFlowState initialized = state("ASKING", 0, 3);

        when(cacheService.getInterviewFlow("s-1")).thenReturn(null, initialized);

        InterviewFlowState state = stateMachine.ensureInitialized("s-1", 3);

        assertEquals("ASKING", state.getStatus());
        verify(cacheService).initInterviewFlow("s-1", 3);
    }

    @Test
    void shouldRejectIllegalTransitionFromCompletedToEvaluating() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = new InterviewFlowStateMachine(cacheService);
        when(cacheService.getInterviewFlow("s-2")).thenReturn(state("COMPLETED", 1, 2));

        assertThrows(IllegalStateException.class, () -> stateMachine.moveToEvaluating("s-2"));
        verify(cacheService, never()).updateInterviewFlowStatus("s-2", "EVALUATING");
    }

    @Test
    void shouldIdentifyOutOfRangeFlow() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = new InterviewFlowStateMachine(cacheService);

        assertTrue(stateMachine.isOutOfRange(state("ASKING", 3, 3)));
    }

    @Test
    void shouldMarkCompletedWhenAdvanceReachesEnd() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = new InterviewFlowStateMachine(cacheService);
        InterviewFlowState evaluating = state("EVALUATING", 2, 3);
        InterviewFlowState completed = state("COMPLETED", 2, 3);

        when(cacheService.getInterviewFlow("s-3")).thenReturn(evaluating, completed);
        when(cacheService.advanceToNextQuestion("s-3")).thenReturn(completed);
        when(cacheService.markInterviewCompleted("s-3")).thenReturn(completed);

        InterviewFlowState next = stateMachine.advanceMainQuestion("s-3");

        assertEquals("COMPLETED", next.getStatus());
        verify(cacheService).markInterviewCompleted("s-3");
    }

    private InterviewFlowState state(String status, int currentIndex, int totalQuestions) {
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(status);
        state.setCurrentIndex(currentIndex);
        state.setTotalQuestions(totalQuestions);
        return state;
    }
}
