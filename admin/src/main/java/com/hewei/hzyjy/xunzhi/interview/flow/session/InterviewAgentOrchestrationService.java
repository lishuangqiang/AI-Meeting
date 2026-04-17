package com.hewei.hzyjy.xunzhi.interview.flow.session;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.InterviewWorkflowService;
import com.hewei.hzyjy.xunzhi.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewAnswerPipeline;
import com.hewei.hzyjy.xunzhi.interview.flow.answer.InterviewQuestionLockService;
import com.hewei.hzyjy.xunzhi.interview.flow.demeanor.InterviewDemeanorService;
import com.hewei.hzyjy.xunzhi.interview.flow.extraction.InterviewQuestionExtractionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Interview orchestration service, including main-question and follow-up restoration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewAgentOrchestrationService implements InterviewWorkflowService {

    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewQuestionExtractionService interviewQuestionExtractionService;
    private final InterviewDemeanorService interviewDemeanorService;
    private final InterviewAnswerPipeline interviewAnswerPipeline;
    private final InterviewFlowStateMachine interviewFlowStateMachine;
    private final InterviewQuestionLockService interviewQuestionLockService;

    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        // 编排入口：提取链路直接委托 extraction flow。
        return interviewQuestionExtractionService.extractInterviewQuestions(reqDTO);
    }

    public InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam) {
        // 编排入口：答题链路统一走 pipeline，避免控制逻辑分散在多个 service。
        return interviewAnswerPipeline.execute(sessionId, requestParam);
    }

    public InterviewAnswerRespDTO getNextQuestion(String sessionId) {
        return getCurrentQuestion(sessionId);
    }

    /**
     * Resolve current question by sessionId without moving the flow cursor.
     * Priority:
     * 1) flow state in Redis
     * 2) latest turn log recovery
     * 3) fallback to first question
     */
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();

        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId cannot be empty");
        }

        try {
            // 1) 先保证题库可用（缓存 miss 时从 DB 回补）。
            Map<String, String> questions = getOrLoadQuestions(sessionId);
            if (questions == null || questions.isEmpty()) {
                return response.fail("interview questions not found");
            }

            InterviewFlowState flowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            if (flowState == null) {
                // 2) flow 丢失时按 turns 恢复；恢复失败再兜底重建 flow。
                CurrentQuestionState recoveredState = recoverCurrentQuestionFromTurns(sessionId, questions);
                if (recoveredState.finished) {
                    Metrics.counter("flow_restore_source_total", "source", "turn_finished").increment();
                    response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
                    return response.finish().success();
                }
                if (recoveredState.hasQuestion()) {
                    Metrics.counter("flow_restore_source_total", "source", "turn_recovered").increment();
                    flowState = restoreFlowToQuestion(sessionId, recoveredState.questionNumber, questions.size());
                    fillCurrentQuestionResponse(sessionId, response,
                            recoveredState.questionNumber, recoveredState.questionContent, flowState);
                    return response.success();
                }

                Metrics.counter("flow_restore_source_total", "source", "flow_reinit").increment();
                interviewQuestionCacheService.initInterviewFlow(sessionId, questions.size());
                flowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            } else {
                Metrics.counter("flow_restore_source_total", "source", "flow_cache").increment();
            }

            if (flowState == null) {
                return response.fail("interview flow not initialized");
            }
            if (flowState.isCompleted() || interviewFlowStateMachine.isOutOfRange(flowState)) {
                response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
                return response.finish().success();
            }

            // 3) 在当前 flow 上解析题号并回填给前端，不推进游标。
            String questionNumber = interviewFlowStateMachine.currentQuestionNumber(flowState);
            String questionContent = resolveQuestionByNumber(sessionId, questionNumber, questions);
            if (StrUtil.isBlank(questionContent)) {
                return response.fail("question does not exist or expired");
            }

            fillCurrentQuestionResponse(sessionId, response, questionNumber, questionContent, flowState);
            return response.success();
        } catch (Exception e) {
            log.error("Failed to get current question, sessionId: {}", sessionId, e);
            return response.fail("failed to get current question: " + e.getMessage());
        }
    }

    public String evaluateDemeanor(DemeanorEvaluationReqDTO reqDTO) {
        return interviewDemeanorService.evaluateDemeanor(reqDTO);
    }

    private void fillCurrentQuestionResponse(
            String sessionId,
            InterviewAnswerRespDTO response,
            String questionNumber,
            String questionContent,
            InterviewFlowState flowState) {
        response.withCurrentQuestion(questionNumber, questionContent);
        response.withNextQuestion(
                questionNumber,
                questionContent,
                isFollowUpQuestion(questionNumber),
                resolveFollowUpCount(flowState, questionNumber)
        );
        response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
    }

    private Map<String, String> getOrLoadQuestions(String sessionId) {
        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        return questions;
    }

    private String resolveQuestionByNumber(String sessionId, String questionNumber, Map<String, String> questions) {
        String normalizedQuestionNumber = normalizeQuestionNumber(questionNumber);
        if (StrUtil.isBlank(normalizedQuestionNumber)) {
            return null;
        }

        String questionContent = questions == null ? null : questions.get(normalizedQuestionNumber);
        if (StrUtil.isBlank(questionContent)) {
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, normalizedQuestionNumber);
        }
        if (StrUtil.isBlank(questionContent) && !normalizedQuestionNumber.equals(questionNumber)) {
            questionContent = questions == null ? null : questions.get(questionNumber);
            if (StrUtil.isBlank(questionContent)) {
                questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
            }
        }
        return questionContent;
    }

    private CurrentQuestionState recoverCurrentQuestionFromTurns(String sessionId, Map<String, String> questions) {
        List<InterviewTurnLog> turns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (turns == null || turns.isEmpty()) {
            return CurrentQuestionState.empty();
        }

        InterviewTurnLog latestTurn = turns.get(turns.size() - 1);
        if (latestTurn == null) {
            return CurrentQuestionState.empty();
        }
        if (Boolean.TRUE.equals(latestTurn.getFinished())) {
            return CurrentQuestionState.finished();
        }

        String nextQuestionNumber = normalizeQuestionNumber(latestTurn.getNextQuestionNumber());
        String nextQuestionContent = resolveQuestionByNumber(sessionId, nextQuestionNumber, questions);
        if (StrUtil.isNotBlank(nextQuestionNumber) && StrUtil.isNotBlank(nextQuestionContent)) {
            return CurrentQuestionState.of(nextQuestionNumber, nextQuestionContent);
        }

        String nextQuestionFromTurn = latestTurn.getNextQuestion();
        if (StrUtil.isNotBlank(nextQuestionFromTurn)) {
            String matchedQuestionNumber = matchQuestionNumberByContent(sessionId, nextQuestionFromTurn, questions);
            if (StrUtil.isNotBlank(matchedQuestionNumber)) {
                String matchedQuestionContent = resolveQuestionByNumber(sessionId, matchedQuestionNumber, questions);
                if (StrUtil.isNotBlank(matchedQuestionContent)) {
                    return CurrentQuestionState.of(matchedQuestionNumber, matchedQuestionContent);
                }
            }
        }

        Integer answeredQuestionNo = extractMainQuestionNo(normalizeQuestionNumber(latestTurn.getQuestionNumber()));
        if (answeredQuestionNo != null) {
            int candidateQuestionNo = answeredQuestionNo + 1;
            if (questions != null && !questions.isEmpty() && candidateQuestionNo <= questions.size()) {
                String candidateQuestionNumber = String.valueOf(candidateQuestionNo);
                String candidateQuestionContent = resolveQuestionByNumber(sessionId, candidateQuestionNumber, questions);
                if (StrUtil.isNotBlank(candidateQuestionContent)) {
                    return CurrentQuestionState.of(candidateQuestionNumber, candidateQuestionContent);
                }
            }
        }

        return CurrentQuestionState.empty();
    }

    private InterviewFlowState restoreFlowToQuestion(String sessionId, String questionNumber, int totalQuestions) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber) || totalQuestions <= 0) {
            return null;
        }
        RLock lock = null;
        try {
            // 1) 先按题号加锁恢复，防止并发恢复互相覆盖。
            lock = interviewQuestionLockService.acquire(sessionId, questionNumber);
            if (lock == null) {
                return interviewQuestionCacheService.getInterviewFlow(sessionId);
            }
            InterviewFlowState existingFlowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            if (existingFlowState != null) {
                return existingFlowState;
            }

            // 2) 先初始化 flow，再把主问题游标推进到目标主问题。
            interviewQuestionCacheService.initInterviewFlow(sessionId, totalQuestions);
            Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
            if (mainQuestionNo == null || mainQuestionNo <= 0) {
                return interviewQuestionCacheService.getInterviewFlow(sessionId);
            }

            int targetMainQuestionNo = Math.min(mainQuestionNo, totalQuestions);
            for (int currentMainQuestionNo = 1; currentMainQuestionNo < targetMainQuestionNo; currentMainQuestionNo++) {
                InterviewFlowState advancedFlow = interviewFlowStateMachine.advanceMainQuestion(sessionId);
                if (advancedFlow == null || interviewFlowStateMachine.isCompleted(advancedFlow)) {
                    return advancedFlow;
                }
            }

            // 3) 如果目标是追问题，再补齐 followUp 次数，保证题号与 flow 状态一致。
            if (isFollowUpQuestion(questionNumber)) {
                int followUpCount = extractFollowUpCount(questionNumber);
                for (int index = 0; index < followUpCount; index++) {
                    interviewFlowStateMachine.startFollowUpQuestion(sessionId, questionNumber);
                }
            }
            return interviewQuestionCacheService.getInterviewFlow(sessionId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return interviewQuestionCacheService.getInterviewFlow(sessionId);
        } finally {
            interviewQuestionLockService.release(lock);
        }
    }

    private String matchQuestionNumberByContent(String sessionId, String questionContent, Map<String, String> questions) {
        String matchedMainQuestionNumber = matchQuestionNumberByContent(questionContent, questions);
        if (StrUtil.isNotBlank(matchedMainQuestionNumber)) {
            return matchedMainQuestionNumber;
        }
        Map<String, String> followUpQuestions = interviewQuestionCacheService.getSessionFollowUpQuestions(sessionId);
        return matchQuestionNumberByContent(questionContent, followUpQuestions);
    }

    private String matchQuestionNumberByContent(String questionContent, Map<String, String> questions) {
        if (StrUtil.isBlank(questionContent) || questions == null || questions.isEmpty()) {
            return null;
        }
        String target = questionContent.trim();
        for (Map.Entry<String, String> entry : questions.entrySet()) {
            if (entry == null || StrUtil.isBlank(entry.getValue())) {
                continue;
            }
            if (target.equals(entry.getValue().trim())) {
                return normalizeQuestionNumber(entry.getKey());
            }
        }
        return null;
    }

    private Integer parsePositiveInt(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Integer extractMainQuestionNo(String questionNumber) {
        String mainQuestionNumber = resolveMainQuestionNumber(questionNumber);
        return parsePositiveInt(mainQuestionNumber);
    }

    private Integer resolveFollowUpCount(InterviewFlowState flowState, String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int parsedFollowUpCount = extractFollowUpCount(questionNumber);
        if (parsedFollowUpCount > 0) {
            return parsedFollowUpCount;
        }
        if (flowState != null && flowState.getFollowUpCount() != null) {
            return Math.max(flowState.getFollowUpCount(), 0);
        }
        return 0;
    }

    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().matches("\\d+-F\\d+");
    }

    private int extractFollowUpCount(String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int separatorIndex = questionNumber.indexOf("-F");
        if (separatorIndex < 0 || separatorIndex + 2 >= questionNumber.length()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
    }

    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separatorIndex = normalized.indexOf("-F");
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    private static final class CurrentQuestionState {
        private final boolean finished;
        private final String questionNumber;
        private final String questionContent;

        private CurrentQuestionState(boolean finished, String questionNumber, String questionContent) {
            this.finished = finished;
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
        }

        private static CurrentQuestionState finished() {
            return new CurrentQuestionState(true, null, null);
        }

        private static CurrentQuestionState of(String questionNumber, String questionContent) {
            return new CurrentQuestionState(false, questionNumber, questionContent);
        }

        private static CurrentQuestionState empty() {
            return new CurrentQuestionState(false, null, null);
        }

        private boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}
