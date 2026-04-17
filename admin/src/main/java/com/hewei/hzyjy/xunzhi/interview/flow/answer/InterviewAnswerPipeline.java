package com.hewei.hzyjy.xunzhi.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.xunzhi.interview.application.guard.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleContext;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleDecision;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAnswerPipeline {

    private static final String PROCESSING_MESSAGE = "current request is processing, please retry later";
    private static final String QUESTION_LOCK_MESSAGE = "current question is processing, please retry later";
    private static final String STALE_QUESTION_MESSAGE = "stale question number, please refresh current question";

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewEvaluationService interviewEvaluationService;
    private final InterviewFollowUpService interviewFollowUpService;
    private final InterviewResponseParser interviewResponseParser;
    private final InterviewFlowStateMachine interviewFlowStateMachine;
    private final InterviewAnswerIdempotencyService interviewAnswerIdempotencyService;
    private final InterviewQuestionLockService interviewQuestionLockService;
    private final InterviewFollowUpRuleService interviewFollowUpRuleService;
    private final InterviewTurnRepairService interviewTurnRepairService;

    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerPipelineContext ctx = new InterviewAnswerPipelineContext();
        ctx.sessionId = sessionId;
        ctx.requestParam = requestParam;
        ctx.response = InterviewAnswerRespDTO.init();

        try {
            // 1) 基础参数校验。
            if (!validateRequest(ctx)) {
                return ctx.response;
            }
            // 2) 归一化 requestId，保证幂等键稳定。
            normalizeRequestId(ctx);
            // 3) 幂等门禁：命中已成功请求直接回放，处理中请求快速失败。
            if (!stepIdempotency(ctx)) {
                return ctx.response;
            }
            // 4) 读取当前题与 flow，拒绝过期题号。
            if (!stepLoadCurrentQuestion(ctx)) {
                return finishAndReturn(ctx, false);
            }
            // 5) 以“当前题号”为粒度加锁，串行化同题并发提交。
            if (!stepAcquireQuestionLock(ctx)) {
                return ctx.response;
            }
            // 6) 加锁后再次校验题号，避免锁前后游标漂移导致串题。
            if (!stepValidateQuestionAfterLock(ctx)) {
                return ctx.response;
            }
            // 7) 调评分链路并提取结构化评分结果（此时仅计算，不入账）。
            if (!stepEvaluateAndScore(ctx)) {
                return ctx.response;
            }
            // 8) 推进 flow、提交分数并组装下一题/结束态响应。
            if (!stepAdvanceFlowAndAssemble(ctx)) {
                return ctx.response;
            }
            return finishAndReturn(ctx, true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while executing interview answer pipeline, sessionId: {}", sessionId);
            recordAnswerPipelineFailure("interrupted");
            ctx.response.fail("interview answer request interrupted");
            return ctx.response;
        } catch (Exception ex) {
            log.error("Failed to execute interview answer pipeline, sessionId: {}", sessionId, ex);
            recordAnswerPipelineFailure("unexpected_exception");
            return ctx.response.fail("failed to process answer: " + ex.getMessage());
        } finally {
            interviewQuestionLockService.release(ctx.questionLock);
            if (ctx.idempotencyStarted && !ctx.idempotencyMarkedSucceeded) {
                interviewAnswerIdempotencyService.clearProcessing(ctx.sessionId, ctx.requestId);
            }
        }
    }

    private InterviewAnswerRespDTO finishAndReturn(InterviewAnswerPipelineContext ctx, boolean appendTurn) {
        if (Boolean.TRUE.equals(ctx.response.getIsSuccess())) {
            if (appendTurn && !stepAppendTurnLog(ctx)) {
                interviewTurnRepairService.enqueue(ctx.sessionId, ctx.turnLog, "append_failed_before_mark_succeeded");
            }
            interviewAnswerIdempotencyService.markSucceeded(ctx.sessionId, ctx.requestId, ctx.response);
            ctx.idempotencyMarkedSucceeded = true;
        }
        return ctx.response;
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
        if (StrUtil.isBlank(ctx.requestParam.getQuestionNumber())) {
            ctx.response.fail("question number cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getAnswerContent())) {
            ctx.response.fail("answer content cannot be empty");
            return false;
        }
        return true;
    }

    private void normalizeRequestId(InterviewAnswerPipelineContext ctx) {
        String requestId = ctx.requestParam.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            String seed = ctx.sessionId + "|" + ctx.requestParam.getQuestionNumber().trim() + "|" + ctx.requestParam.getAnswerContent();
            requestId = "auto-" + DigestUtil.sha256Hex(seed).substring(0, 32);
            ctx.requestParam.setRequestId(requestId);
        } else {
            requestId = requestId.trim();
            ctx.requestParam.setRequestId(requestId);
        }
        ctx.requestId = requestId;
    }

    private boolean stepIdempotency(InterviewAnswerPipelineContext ctx) {
        InterviewAnswerIdempotencyService.TryStartResult tryStartResult =
                interviewAnswerIdempotencyService.tryStart(ctx.sessionId, ctx.requestId);
        switch (tryStartResult.getStatus()) {
            case SUCCEEDED -> {
                InterviewAnswerRespDTO replayResponse = tryStartResult.getReplayResponse();
                if (replayResponse != null) {
                    Metrics.counter("idempotency_replay_hit_total").increment();
                    ctx.response = replayResponse;
                    return false;
                }
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case PROCESSING -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case NEW -> {
                ctx.idempotencyStarted = true;
                return true;
            }
            default -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
        }
    }

    private boolean stepAcquireQuestionLock(InterviewAnswerPipelineContext ctx) throws InterruptedException {
        RLock questionLock = interviewQuestionLockService.acquire(ctx.sessionId, ctx.currentQuestionNumber);
        if (questionLock == null) {
            Metrics.counter("question_lock_contention_total").increment();
            recordAnswerPipelineFailure("question_lock_contention");
            ctx.response.fail(QUESTION_LOCK_MESSAGE);
            return false;
        }
        ctx.questionLock = questionLock;
        return true;
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

        ctx.currentIsFollowUp = isFollowUpQuestion(ctx.currentQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(ctx.flowState, ctx.currentQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(ctx.flowState);
        ctx.response.withCurrentQuestion(ctx.currentQuestionNumber, ctx.currentQuestion);
        if (!isRequestedQuestionCurrent(ctx.requestParam.getQuestionNumber(), ctx.currentQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        return true;
    }

    private boolean stepValidateQuestionAfterLock(InterviewAnswerPipelineContext ctx) {
        InterviewFlowState lockedFlow = interviewFlowStateMachine.current(ctx.sessionId);
        if (lockedFlow == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        String lockedQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(lockedFlow);
        if (!isRequestedQuestionCurrent(ctx.currentQuestionNumber, lockedQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject_after_lock");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        String lockedQuestionContent = getQuestionWithReload(ctx.sessionId, lockedQuestionNumber);
        if (StrUtil.isBlank(lockedQuestionContent)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }
        ctx.flowState = lockedFlow;
        ctx.currentQuestionNumber = lockedQuestionNumber;
        ctx.currentQuestion = lockedQuestionContent;
        ctx.currentIsFollowUp = isFollowUpQuestion(lockedQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(lockedFlow, lockedQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(lockedFlow);
        return true;
    }

    private boolean stepEvaluateAndScore(InterviewAnswerPipelineContext ctx) {
        interviewFlowStateMachine.moveToEvaluating(ctx.sessionId);

        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION);
        if (agentProperties == null) {
            recordAnswerPipelineFailure("agent_config_missing");
            ctx.response.fail("agent configuration does not exist");
            return false;
        }

        Map<String, Object> evaluationResult;
        try {
            evaluationResult = interviewEvaluationService.evaluateAnswer(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    agentProperties
            );
        } catch (InterviewAiGuardException guardException) {
            recordAnswerPipelineFailure("ai_guard_" + guardException.getErrorCode().name().toLowerCase());
            ctx.response.fail(guardException.getMessage());
            return false;
        }
        if (evaluationResult == null) {
            recordAnswerPipelineFailure("evaluation_parse_failed");
            ctx.response.fail("failed to parse evaluation result");
            return false;
        }

        Integer score = interviewResponseParser.parseScoreFromResponse(evaluationResult, "score");
        if (score == null) {
            recordAnswerPipelineFailure("evaluation_score_missing");
            ctx.response.fail("score missing in evaluation result");
            return false;
        }

        ctx.followUpNeeded = interviewResponseParser.asBoolean(evaluationResult.get("follow_up_needed"));
        ctx.followUpQuestion = sanitizeFollowUpQuestion(interviewResponseParser.asString(evaluationResult.get("follow_up_question")));
        ctx.missingPoints = interviewResponseParser.asStringList(evaluationResult.get("missing_points"));

        ctx.score = score;
        ctx.totalScore = interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId);
        ctx.response.withEvaluation(score, interviewResponseParser.asString(evaluationResult.get("feedback")), ctx.totalScore);
        return true;
    }

    private boolean stepAdvanceFlowAndAssemble(InterviewAnswerPipelineContext ctx) {
        // 1) 先拍快照：后续若计分提交失败，用于补偿回滚 flow，避免“题号推进成功但分数未入账”。
        InterviewFlowState flowSnapshotBeforeAdvance = snapshotFlowState(interviewFlowStateMachine.current(ctx.sessionId));
        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(ctx);
        int resolvedMaxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : ctx.maxFollowUp;
        boolean needFollowUp = ruleDecision != null
                ? ruleDecision.isNeedFollowUp()
                : Boolean.TRUE.equals(ctx.followUpNeeded);

        log.info(
                "Follow-up rule decision, sessionId={}, requestId={}, questionNumber={}, chainId={}, reasonCode={}, reasonText={}, ruleVersion={}, needFollowUp={}, resolvedMaxFollowUp={}, fallback={}",
                ctx.sessionId,
                ctx.requestId,
                ctx.currentQuestionNumber,
                ruleDecision == null ? null : ruleDecision.getChainId(),
                ruleDecision == null ? null : ruleDecision.getReasonCode(),
                ruleDecision == null ? null : ruleDecision.getReasonText(),
                ruleDecision == null ? null : ruleDecision.getRuleVersion(),
                needFollowUp,
                resolvedMaxFollowUp,
                ruleDecision != null && ruleDecision.isFallback()
        );

        // 2) 按规则优先走追问分支；追问生成失败则自动回落到主问题推进分支。
        if (needFollowUp && ctx.currentFollowUpCount < resolvedMaxFollowUp) {
            InterviewFollowUpService.FollowUpQuestionResult followUpQuestionResult = interviewFollowUpService.generateFollowUpQuestion(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    ctx.followUpQuestion,
                    ctx.currentFollowUpCount,
                    resolvedMaxFollowUp
            );
            if (followUpQuestionResult.hasQuestion()) {
                interviewQuestionCacheService.cacheFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent()
                );
                InterviewFlowState followUpFlow = interviewFlowStateMachine.startFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber()
                );
                Integer nextFollowUpCount = followUpFlow != null && followUpFlow.getFollowUpCount() != null
                        ? followUpFlow.getFollowUpCount()
                        : followUpQuestionResult.getFollowUpCount();
                if (!commitScoreAtSuccess(ctx)) {
                    rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "followup");
                    return false;
                }
                ctx.response.withNextQuestion(
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent(),
                        true,
                        nextFollowUpCount
                ).success();
                return true;
            }
        }

        // 3) 无追问时推进主问题；到末题则标记完成并返回 finish。
        InterviewFlowState nextFlow = interviewFlowStateMachine.advanceMainQuestion(ctx.sessionId);
        if (nextFlow == null || interviewFlowStateMachine.isCompleted(nextFlow)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            if (!commitScoreAtSuccess(ctx)) {
                rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "finish");
                return false;
            }
            ctx.response.finish().success();
            return true;
        }

        String nextQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(nextFlow);
        String nextQuestion = getQuestionWithReload(ctx.sessionId, nextQuestionNumber);
        if (StrUtil.isBlank(nextQuestion)) {
            ctx.response.fail("next question does not exist or expired");
            return false;
        }

        if (!commitScoreAtSuccess(ctx)) {
            rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "next_main");
            return false;
        }
        ctx.response.withNextQuestion(nextQuestionNumber, nextQuestion, false, 0).success();
        return true;
    }

    private boolean commitScoreAtSuccess(InterviewAnswerPipelineContext ctx) {
        try {
            // 追问不计入总分；仅主问题在“返回成功前”提交入账，避免失败重试重复计分。
            Integer committedTotalScore = Boolean.TRUE.equals(ctx.currentIsFollowUp)
                    ? interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId)
                    : interviewQuestionCacheService.addSessionScore(ctx.sessionId, ctx.score);
            ctx.totalScore = committedTotalScore;
            ctx.response.setTotalScore(committedTotalScore);
            return true;
        } catch (Exception ex) {
            log.error("Failed to commit interview score, sessionId={}, requestId={}", ctx.sessionId, ctx.requestId, ex);
            recordAnswerPipelineFailure("score_commit_failed");
            ctx.response.fail("failed to commit interview score");
            return false;
        }
    }

    private void rollbackFlowAfterCommitFailure(
            InterviewAnswerPipelineContext ctx,
            InterviewFlowState flowSnapshotBeforeAdvance,
            String branch) {
        if (flowSnapshotBeforeAdvance == null) {
            return;
        }
        try {
            // 分数提交失败时，恢复到推进前状态，保证客户端重试仍能命中当前题。
            interviewQuestionCacheService.restoreInterviewFlow(ctx.sessionId, flowSnapshotBeforeAdvance);
            Metrics.counter("answer_flow_rollback_total", "branch", StrUtil.blankToDefault(branch, "unknown")).increment();
            log.warn("Rolled back interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch);
        } catch (Exception ex) {
            log.error("Failed to rollback interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch, ex);
        }
    }

    private InterviewFlowState snapshotFlowState(InterviewFlowState state) {
        if (state == null) {
            return null;
        }
        // 手动复制，避免后续对象被原地修改导致“快照”失效。
        InterviewFlowState snapshot = new InterviewFlowState();
        snapshot.setStatus(state.getStatus());
        snapshot.setCurrentIndex(state.getCurrentIndex());
        snapshot.setCurrentQuestionNumber(state.getCurrentQuestionNumber());
        snapshot.setTotalQuestions(state.getTotalQuestions());
        snapshot.setFollowUpCount(state.getFollowUpCount());
        snapshot.setMaxFollowUp(state.getMaxFollowUp());
        snapshot.setVersion(state.getVersion());
        return snapshot;
    }

    private InterviewFollowUpRuleDecision decideFollowUp(InterviewAnswerPipelineContext ctx) {
        InterviewFollowUpRuleContext ruleContext = new InterviewFollowUpRuleContext();
        ruleContext.setSessionId(ctx.sessionId);
        ruleContext.setRequestId(ctx.requestId);
        ruleContext.setQuestionNumber(ctx.currentQuestionNumber);
        ruleContext.setInterviewType(interviewQuestionCacheService.getSessionInterviewDirection(ctx.sessionId));
        ruleContext.setFollowUpQuestion(Boolean.TRUE.equals(ctx.currentIsFollowUp));
        ruleContext.setFollowUpCount(ctx.currentFollowUpCount == null ? 0 : Math.max(ctx.currentFollowUpCount, 0));
        ruleContext.setMaxFollowUp(ctx.maxFollowUp == null ? 2 : Math.max(ctx.maxFollowUp, 1));
        ruleContext.setScore(ctx.score);
        ruleContext.setFollowUpNeededFromAi(Boolean.TRUE.equals(ctx.followUpNeeded));
        ruleContext.setMissingPoints(ctx.missingPoints);
        ruleContext.setFollowUpQuestionHint(ctx.followUpQuestion);
        ruleContext.setInterviewCompleted(Boolean.TRUE.equals(ctx.response.getFinished()));
        ctx.followUpRuleDecision = interviewFollowUpRuleService.decide(ruleContext);
        return ctx.followUpRuleDecision;
    }

    private boolean stepAppendTurnLog(InterviewAnswerPipelineContext ctx) {
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
                    .followUpNeeded(ctx.followUpNeeded)
                    .isFollowUp(ctx.currentIsFollowUp)
                    .followUpCount(ctx.currentFollowUpCount)
                    .nextQuestionNumber(ctx.response.getNextQuestionNumber())
                    .nextQuestion(ctx.response.getNextQuestion())
                    .finished(ctx.response.getFinished())
                    .build();
            ctx.turnLog = turn;
            return interviewQuestionCacheService.appendInterviewTurnIfAbsent(ctx.sessionId, turn);
        } catch (Exception ex) {
            log.warn("Failed to append interview turn, sessionId: {}", ctx.sessionId, ex);
            return false;
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

    private String getQuestionWithReload(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        if (StrUtil.isBlank(questionContent) && !isFollowUpQuestion(questionNumber)) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        }
        return questionContent;
    }

    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().matches("\\d+-F\\d+");
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

    private int resolveMaxFollowUp(InterviewFlowState flowState) {
        if (flowState == null || flowState.getMaxFollowUp() == null || flowState.getMaxFollowUp() <= 0) {
            return 2;
        }
        return flowState.getMaxFollowUp();
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

    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private String truncateForLog(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void recordAnswerPipelineFailure(String reason) {
        String safeReason = StrUtil.isBlank(reason) ? "unknown" : reason.trim();
        Metrics.counter("answer_pipeline_fail_total", "reason", safeReason).increment();
    }

    private boolean isRequestedQuestionCurrent(String requestedQuestion, String currentQuestion) {
        String normalizedRequested = normalizeQuestionNumber(requestedQuestion);
        String normalizedCurrent = normalizeQuestionNumber(currentQuestion);
        return Objects.equals(normalizedRequested, normalizedCurrent);
    }

    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        if (normalized.matches("\\d+-F\\d+")) {
            String[] parts = normalized.split("-F");
            try {
                return Integer.parseInt(parts[0]) + "-F" + Integer.parseInt(parts[1]);
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    private static final class InterviewAnswerPipelineContext {
        private String sessionId;
        private InterviewAnswerReqDTO requestParam;
        private InterviewAnswerRespDTO response;
        private String requestId;
        private InterviewFlowState flowState;
        private String currentQuestionNumber;
        private String currentQuestion;
        private Boolean currentIsFollowUp;
        private Integer currentFollowUpCount;
        private Integer maxFollowUp;
        private Integer score;
        private Integer totalScore;
        private Boolean followUpNeeded;
        private String followUpQuestion;
        private List<String> missingPoints;
        private InterviewFollowUpRuleDecision followUpRuleDecision;
        private InterviewTurnLog turnLog;
        private boolean idempotencyStarted;
        private boolean idempotencyMarkedSucceeded;
        private RLock questionLock;
    }
}
