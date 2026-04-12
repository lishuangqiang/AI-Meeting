package com.hewei.hzyjy.xunzhi.interview.application.pipeline;

import cn.hutool.core.util.StrUtil;
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
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAnswerPipeline {

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewEvaluationService interviewEvaluationService;
    private final InterviewResponseParser interviewResponseParser;
    private final InterviewFlowStateMachine interviewFlowStateMachine;

    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerPipelineContext ctx = new InterviewAnswerPipelineContext();
        ctx.sessionId = sessionId;
        ctx.requestParam = requestParam;
        ctx.response = InterviewAnswerRespDTO.init();

        try {
            if (!validateRequest(ctx)) {
                return ctx.response;
            }
            if (!stepIdempotency(ctx)) {
                return ctx.response;
            }
            if (!stepLoadCurrentQuestion(ctx)) {
                return ctx.response;
            }
            if (!stepEvaluateAndScore(ctx)) {
                return ctx.response;
            }
            if (!stepAdvanceFlowAndAssemble(ctx)) {
                return ctx.response;
            }
            stepAppendTurnLog(ctx);
            return ctx.response;
        } catch (Exception ex) {
            log.error("Failed to execute interview answer pipeline, sessionId: {}", sessionId, ex);
            return ctx.response.fail("failed to process answer: " + ex.getMessage());
        }
    }

    private boolean validateRequest(InterviewAnswerPipelineContext ctx) {
        if (StrUtil.isBlank(ctx.sessionId)) {
            ctx.response.fail("sessionId cannot be empty");
            return false;
        }
        if (ctx.requestParam == null) {
            ctx.response.fail("request body cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getAnswerContent())) {
            ctx.response.fail("answer content cannot be empty");
            return false;
        }
        ctx.requestId = ctx.requestParam.getRequestId();
        return true;
    }

    private boolean stepIdempotency(InterviewAnswerPipelineContext ctx) {
        if (interviewQuestionCacheService.markAnswerRequestProcessed(ctx.sessionId, ctx.requestId)) {
            return true;
        }

        ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
        fillNextQuestionFromFlow(ctx.sessionId, ctx.response);
        if (StrUtil.isBlank(ctx.response.getErrorMessage())) {
            ctx.response.success();
        }
        return false;
    }

    private boolean stepLoadCurrentQuestion(InterviewAnswerPipelineContext ctx) {
        ctx.flowState = ensureInterviewFlow(ctx.sessionId);
        if (ctx.flowState == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        if (interviewFlowStateMachine.isCompleted(ctx.flowState)) {
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }
        if (interviewFlowStateMachine.isOutOfRange(ctx.flowState)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }

        ctx.currentQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(ctx.flowState);
        ctx.currentQuestion = getQuestionWithReload(ctx.sessionId, ctx.currentQuestionNumber);
        if (StrUtil.isBlank(ctx.currentQuestion)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }
        ctx.response.withCurrentQuestion(ctx.currentQuestionNumber, ctx.currentQuestion);
        return true;
    }

    private boolean stepEvaluateAndScore(InterviewAnswerPipelineContext ctx) {
        interviewFlowStateMachine.moveToEvaluating(ctx.sessionId);

        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION);
        if (agentProperties == null) {
            ctx.response.fail("agent configuration does not exist");
            return false;
        }

        Map<String, Object> evaluationResult = interviewEvaluationService.evaluateAnswer(
                ctx.sessionId,
                ctx.requestId,
                ctx.currentQuestionNumber,
                ctx.currentQuestion,
                ctx.requestParam.getAnswerContent(),
                agentProperties
        );
        if (evaluationResult == null) {
            ctx.response.fail("failed to parse evaluation result");
            return false;
        }

        Integer score = interviewResponseParser.parseScoreFromResponse(evaluationResult, "score");
        if (score == null) {
            ctx.response.fail("score missing in evaluation result");
            return false;
        }

        Integer totalScore = interviewQuestionCacheService.addSessionScore(ctx.sessionId, score);
        ctx.score = score;
        ctx.totalScore = totalScore;
        ctx.response.withEvaluation(score, interviewResponseParser.asString(evaluationResult.get("feedback")), totalScore);
        return true;
    }

    private boolean stepAdvanceFlowAndAssemble(InterviewAnswerPipelineContext ctx) {
        InterviewFlowState nextFlow = interviewFlowStateMachine.advanceMainQuestion(ctx.sessionId);
        if (nextFlow == null || interviewFlowStateMachine.isCompleted(nextFlow)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            ctx.response.finish().success();
            return true;
        }

        String nextQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(nextFlow);
        String nextQuestion = getQuestionWithReload(ctx.sessionId, nextQuestionNumber);
        if (StrUtil.isBlank(nextQuestion)) {
            ctx.response.fail("next question does not exist or expired");
            return false;
        }

        ctx.response.withNextQuestion(nextQuestionNumber, nextQuestion, false, 0).success();
        return true;
    }

    private void stepAppendTurnLog(InterviewAnswerPipelineContext ctx) {
        try {
            InterviewTurnLog turn = InterviewTurnLog.builder()
                    .timestamp(System.currentTimeMillis())
                    .requestId(ctx.requestId)
                    .questionNumber(ctx.currentQuestionNumber)
                    .questionContent(ctx.currentQuestion)
                    .answerContent(truncateForLog(ctx.requestParam.getAnswerContent(), 1000))
                    .score(ctx.score)
                    .totalScore(ctx.totalScore)
                    .feedback(ctx.response.getFeedback())
                    .followUpNeeded(false)
                    .isFollowUp(false)
                    .followUpCount(0)
                    .nextQuestionNumber(ctx.response.getNextQuestionNumber())
                    .nextQuestion(ctx.response.getNextQuestion())
                    .finished(ctx.response.getFinished())
                    .build();
            interviewQuestionCacheService.appendInterviewTurn(ctx.sessionId, turn);
        } catch (Exception ex) {
            log.warn("Failed to append interview turn, sessionId: {}", ctx.sessionId, ex);
        }
    }

    private InterviewFlowState ensureInterviewFlow(String sessionId) {
        InterviewFlowState state = interviewFlowStateMachine.current(sessionId);
        if (state != null) {
            return state;
        }

        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        return interviewFlowStateMachine.ensureInitialized(sessionId, questions.size());
    }

    private void fillNextQuestionFromFlow(String sessionId, InterviewAnswerRespDTO response) {
        InterviewFlowState state = interviewFlowStateMachine.current(sessionId);
        if (state == null || interviewFlowStateMachine.isCompleted(state) || interviewFlowStateMachine.isOutOfRange(state)) {
            response.finish();
            return;
        }
        String questionNumber = interviewFlowStateMachine.currentQuestionNumber(state);
        String nextQuestion = getQuestionWithReload(sessionId, questionNumber);
        if (StrUtil.isBlank(nextQuestion)) {
            response.fail("question does not exist or expired");
            return;
        }
        response.withNextQuestion(questionNumber, nextQuestion, false, 0);
    }

    private String getQuestionWithReload(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        if (StrUtil.isBlank(questionContent)) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        }
        return questionContent;
    }

    private String truncateForLog(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final class InterviewAnswerPipelineContext {
        private String sessionId;
        private InterviewAnswerReqDTO requestParam;
        private InterviewAnswerRespDTO response;
        private String requestId;
        private InterviewFlowState flowState;
        private String currentQuestionNumber;
        private String currentQuestion;
        private Integer score;
        private Integer totalScore;
    }
}
