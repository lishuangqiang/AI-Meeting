package com.hewei.hzyjy.xunzhi.interview.application.pipeline;

import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.InterviewEvaluationService;
import com.hewei.hzyjy.xunzhi.interview.application.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewAnswerPipelineTest {

    @Test
    void shouldReturnIdempotentResponseWhenRequestProcessed() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = mock(InterviewFlowStateMachine.class);
        InterviewAnswerPipeline pipeline = newPipeline(cacheService, stateMachine);

        InterviewFlowState asking = state("ASKING", 1, 3);
        when(cacheService.markAnswerRequestProcessed("s-1", "r-1")).thenReturn(false);
        when(cacheService.getSessionTotalScore("s-1")).thenReturn(66);
        when(stateMachine.current("s-1")).thenReturn(asking);
        when(stateMachine.isCompleted(asking)).thenReturn(false);
        when(stateMachine.isOutOfRange(asking)).thenReturn(false);
        when(stateMachine.currentQuestionNumber(asking)).thenReturn("2");
        when(cacheService.getQuestionByNumber("s-1", "2")).thenReturn("question-2");

        InterviewAnswerRespDTO response = pipeline.execute("s-1", request("r-1", "answer"));

        assertTrue(Boolean.TRUE.equals(response.getIsSuccess()));
        assertEquals(66, response.getTotalScore());
        assertEquals("2", response.getNextQuestionNumber());
        assertEquals("question-2", response.getNextQuestion());
        verify(cacheService, never()).addSessionScore(any(), any());
    }

    @Test
    void shouldAdvanceToNextQuestionWhenEvaluationSucceeded() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = mock(InterviewFlowStateMachine.class);
        BusinessAgentResolver resolver = mock(BusinessAgentResolver.class);
        InterviewEvaluationService evaluationService = mock(InterviewEvaluationService.class);
        InterviewResponseParser parser = new InterviewResponseParser();
        InterviewAnswerPipeline pipeline = new InterviewAnswerPipeline(
                resolver,
                cacheService,
                evaluationService,
                parser,
                stateMachine
        );

        InterviewFlowState asking = state("ASKING", 0, 2);
        InterviewFlowState next = state("ASKING", 1, 2);
        AgentPropertiesDO agent = new AgentPropertiesDO();

        when(cacheService.markAnswerRequestProcessed("s-2", "r-2")).thenReturn(true);
        when(stateMachine.current("s-2")).thenReturn(asking);
        when(stateMachine.isCompleted(asking)).thenReturn(false);
        when(stateMachine.isOutOfRange(asking)).thenReturn(false);
        when(stateMachine.currentQuestionNumber(asking)).thenReturn("1");
        when(cacheService.getQuestionByNumber("s-2", "1")).thenReturn("question-1");
        when(resolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION)).thenReturn(agent);
        when(evaluationService.evaluateAnswer(
                eq("s-2"),
                eq("r-2"),
                eq("1"),
                eq("question-1"),
                eq("answer"),
                eq(agent)
        )).thenReturn(Map.of("score", 80, "feedback", "good"));
        when(cacheService.addSessionScore("s-2", 80)).thenReturn(80);
        when(stateMachine.advanceMainQuestion("s-2")).thenReturn(next);
        when(stateMachine.isCompleted(next)).thenReturn(false);
        when(stateMachine.currentQuestionNumber(next)).thenReturn("2");
        when(cacheService.getQuestionByNumber("s-2", "2")).thenReturn("question-2");

        InterviewAnswerRespDTO response = pipeline.execute("s-2", request("r-2", "answer"));

        assertTrue(Boolean.TRUE.equals(response.getIsSuccess()));
        assertEquals(80, response.getScore());
        assertEquals(80, response.getTotalScore());
        assertEquals("2", response.getNextQuestionNumber());
        assertEquals("question-2", response.getNextQuestion());
        verify(cacheService).appendInterviewTurn(eq("s-2"), any());
    }

    @Test
    void shouldFinishInterviewWhenFlowCompletedAfterEvaluation() {
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewFlowStateMachine stateMachine = mock(InterviewFlowStateMachine.class);
        BusinessAgentResolver resolver = mock(BusinessAgentResolver.class);
        InterviewEvaluationService evaluationService = mock(InterviewEvaluationService.class);
        InterviewResponseParser parser = new InterviewResponseParser();
        InterviewAnswerPipeline pipeline = new InterviewAnswerPipeline(
                resolver,
                cacheService,
                evaluationService,
                parser,
                stateMachine
        );

        InterviewFlowState asking = state("ASKING", 0, 1);
        InterviewFlowState completed = state("COMPLETED", 0, 1);
        AgentPropertiesDO agent = new AgentPropertiesDO();

        when(cacheService.markAnswerRequestProcessed("s-3", "r-3")).thenReturn(true);
        when(stateMachine.current("s-3")).thenReturn(asking);
        when(stateMachine.isCompleted(asking)).thenReturn(false);
        when(stateMachine.isOutOfRange(asking)).thenReturn(false);
        when(stateMachine.currentQuestionNumber(asking)).thenReturn("1");
        when(cacheService.getQuestionByNumber("s-3", "1")).thenReturn("question-1");
        when(resolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION)).thenReturn(agent);
        when(evaluationService.evaluateAnswer(
                eq("s-3"),
                eq("r-3"),
                eq("1"),
                eq("question-1"),
                eq("answer"),
                eq(agent)
        )).thenReturn(Map.of("score", 90, "feedback", "nice"));
        when(cacheService.addSessionScore("s-3", 90)).thenReturn(90);
        when(stateMachine.advanceMainQuestion("s-3")).thenReturn(completed);
        when(stateMachine.isCompleted(completed)).thenReturn(true);
        when(stateMachine.markCompleted("s-3")).thenReturn(completed);

        InterviewAnswerRespDTO response = pipeline.execute("s-3", request("r-3", "answer"));

        assertTrue(Boolean.TRUE.equals(response.getIsSuccess()));
        assertTrue(Boolean.TRUE.equals(response.getFinished()));
        assertEquals(90, response.getTotalScore());
        verify(cacheService).appendInterviewTurn(eq("s-3"), any());
    }

    private InterviewAnswerPipeline newPipeline(
            InterviewQuestionCacheService cacheService,
            InterviewFlowStateMachine stateMachine) {
        BusinessAgentResolver resolver = mock(BusinessAgentResolver.class);
        InterviewEvaluationService evaluationService = mock(InterviewEvaluationService.class);
        InterviewResponseParser parser = new InterviewResponseParser();
        return new InterviewAnswerPipeline(resolver, cacheService, evaluationService, parser, stateMachine);
    }

    private InterviewAnswerReqDTO request(String requestId, String answerContent) {
        InterviewAnswerReqDTO req = new InterviewAnswerReqDTO();
        req.setRequestId(requestId);
        req.setAnswerContent(answerContent);
        return req;
    }

    private InterviewFlowState state(String status, int currentIndex, int totalQuestions) {
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(status);
        state.setCurrentIndex(currentIndex);
        state.setTotalQuestions(totalQuestions);
        return state;
    }
}
