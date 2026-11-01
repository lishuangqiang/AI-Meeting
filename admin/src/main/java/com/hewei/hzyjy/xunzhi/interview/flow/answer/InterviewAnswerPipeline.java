package com.hewei.hzyjy.xunzhi.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.xunzhi.interview.application.flow.InterviewFlowStatus;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeView;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleContext;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleDecision;
import com.hewei.hzyjy.xunzhi.interview.application.rule.InterviewFollowUpRuleService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitRequest;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitResult;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeLoadMode;
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
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;

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
                return finishAndReturn(ctx);
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
            // 8) 一次 Lua 原子提交：flow 推进、计分、轮次与幂等记录同生共死。
            if (!stepAdvanceFlowAndAssemble(ctx)) {
                return ctx.response;
            }
            return finishAndReturn(ctx);
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

    private InterviewAnswerRespDTO finishAndReturn(InterviewAnswerPipelineContext ctx) {
        if (Boolean.TRUE.equals(ctx.response.getIsSuccess())) {
            // 轮次日志已由原子提交 Lua 脚本写入 Redis；此处仅刷新运行时快照。
            if (ctx.turnLog != null) {
                runtimeSnapshotService.refreshAfterAnswerCommitted(ctx.sessionId, ctx.requestId, ctx.turnLog);
            }
            // 幂等回放由 committedRecords 承担，这里只清理 in-flight 围栏。
            interviewAnswerIdempotencyService.clearProcessing(ctx.sessionId, ctx.requestId);
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
        // 指纹：答案内容摘要，用于幂等记录的内容绑定，防止 requestId 复用串内容。
        String answerContent = ctx.requestParam.getAnswerContent() == null
                ? "" : ctx.requestParam.getAnswerContent().trim();
        ctx.answerDigest = DigestUtil.sha256Hex(answerContent);
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
                interviewAnswerIdempotencyService.tryStart(
                        ctx.sessionId,
                        ctx.requestId,
                        ctx.requestParam.getQuestionNumber(),
                        ctx.answerDigest
                );
        switch (tryStartResult.getStatus()) {
            case SUCCEEDED -> {
                Metrics.counter("idempotency_replay_hit_total").increment();
                InterviewAnswerRespDTO replayResponse = buildReplayResponse(tryStartResult.getReplayRecordJson());
                ctx.response = replayResponse != null ? replayResponse : ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case PROCESSING -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case FINGERPRINT_MISMATCH -> {
                ctx.response.fail("requestId already committed with different content");
                return false;
            }
            case NEW -> {
                ctx.idempotencyStarted = true;
                InterviewAnswerRespDTO replayResponse = runtimeSnapshotService.findReplayResponse(
                        ctx.sessionId,
                        ctx.requestId,
                        ctx.requestParam.getQuestionNumber(),
                        ctx.requestParam.getAnswerContent()
                );
                if (replayResponse != null) {
                    interviewAnswerIdempotencyService.clearProcessing(ctx.sessionId, ctx.requestId);
                    ctx.idempotencyMarkedSucceeded = true;
                    ctx.response = replayResponse;
                    return false;
                }
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
        InterviewSessionRuntimeView runtimeView = runtimeRehydrateService.ensureRuntime(
                ctx.sessionId,
                InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                InterviewRuntimeRehydrateScope.HOT_RUNTIME
        );
        if (runtimeView != null && !runtimeView.canWrite() && !runtimeView.isTerminal()) {
            ctx.response.fail("interview runtime restored as read-only");
            return false;
        }
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
        // 1) 读取当前 flow（moveToEvaluating 之后的最新版本），作为原子提交的 CAS 期望值。
        InterviewFlowState flow = interviewFlowStateMachine.current(ctx.sessionId);
        if (flow == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        int expectedVersion = flow.getVersion() == null ? 0 : flow.getVersion();
        int currentIndex = flow.getCurrentIndex() == null ? 0 : flow.getCurrentIndex();
        int totalQuestions = flow.getTotalQuestions() == null ? 0 : flow.getTotalQuestions();
        int maxFollowUp = flow.getMaxFollowUp() == null ? 2 : flow.getMaxFollowUp();

        // 2) 追问规则决策。
        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(ctx);
        int resolvedMaxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : maxFollowUp;
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

        // 3) 计算提交后的 flow 与下一题信息（全部在 Lua 之前确定，Lua 只负责原子落账）。
        // 默认值仅用于满足确定赋值分析，实际值必然由下方两个分支之一完整覆盖。
        String nextStatus = null;
        int nextIndex = 0;
        String nextQuestionNumber = null;
        String nextQuestion = null;
        boolean nextFinished = false;
        boolean nextIsFollowUp = false;
        int nextFollowUpCount = 0;

        int currentFollowUpCount = ctx.currentFollowUpCount == null ? 0 : ctx.currentFollowUpCount;
        boolean followUpBranch = false;
        if (needFollowUp && currentFollowUpCount < resolvedMaxFollowUp) {
            InterviewFollowUpService.FollowUpQuestionResult followUpQuestionResult = interviewFollowUpService.generateFollowUpQuestion(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    ctx.followUpQuestion,
                    currentFollowUpCount,
                    resolvedMaxFollowUp
            );
            if (followUpQuestionResult.hasQuestion()) {
                interviewQuestionCacheService.cacheFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent()
                );
                nextStatus = InterviewFlowStatus.FOLLOW_UP.name();
                nextIndex = currentIndex;
                nextQuestionNumber = followUpQuestionResult.getQuestionNumber();
                nextQuestion = followUpQuestionResult.getQuestionContent();
                nextFinished = false;
                nextIsFollowUp = true;
                nextFollowUpCount = currentFollowUpCount + 1;
                followUpBranch = true;
            }
        }

        if (!followUpBranch) {
            // 追问生成失败或无追问：推进主问题；到末题则直接进入完成态（由 Lua 原子写入 COMPLETED）。
            int nextIdx = currentIndex + 1;
            if (totalQuestions <= 0 || nextIdx >= totalQuestions) {
                nextStatus = InterviewFlowStatus.COMPLETED.name();
                nextIndex = currentIndex;
                nextQuestionNumber = null;
                nextQuestion = null;
                nextFinished = true;
                nextIsFollowUp = false;
                nextFollowUpCount = 0;
            } else {
                String mainNextNumber = String.valueOf(nextIdx + 1);
                String mainNextQuestion = getQuestionWithReload(ctx.sessionId, mainNextNumber);
                if (StrUtil.isBlank(mainNextQuestion)) {
                    ctx.response.fail("next question does not exist or expired");
                    return false;
                }
                nextStatus = InterviewFlowStatus.ASKING.name();
                nextIndex = nextIdx;
                nextQuestionNumber = mainNextNumber;
                nextQuestion = mainNextQuestion;
                nextFinished = false;
                nextIsFollowUp = false;
                nextFollowUpCount = 0;
            }
        }

        // 4) 构建轮次日志（totalScore 为占位值，由 Lua 按最终 avg 修正后写入）。
        Integer currentTotalScore = interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId);
        InterviewTurnLog turn = buildTurnLog(ctx, nextQuestionNumber, nextQuestion, nextFinished, currentTotalScore);
        ctx.turnLog = turn;

        // 5) 一次 Lua 原子提交：flow CAS 推进 + 分数聚合 + 轮次 + 幂等记录，全部或全不。
        AnswerCommitRequest request = AnswerCommitRequest.builder()
                .sessionId(ctx.sessionId)
                .requestId(ctx.requestId)
                .questionNumber(ctx.requestParam.getQuestionNumber())
                .answerDigest(ctx.answerDigest)
                .shouldScore(!Boolean.TRUE.equals(ctx.currentIsFollowUp))
                .score(ctx.score == null ? 0 : ctx.score)
                .expectedVersion(expectedVersion)
                .status(nextStatus)
                .currentIndex(nextIndex)
                .currentQuestionNumber(nextQuestionNumber)
                .totalQuestions(totalQuestions)
                .followUpCount(nextFollowUpCount)
                .maxFollowUp(maxFollowUp)
                .feedback(ctx.response.getFeedback())
                .nextQuestionNumber(nextQuestionNumber)
                .nextQuestion(nextQuestion)
                .finished(nextFinished)
                .isFollowUp(nextIsFollowUp)
                .currentTotalScore(currentTotalScore)
                .turnPayload(JSON.toJSONString(turn))
                .build();
        AnswerCommitResult commit = interviewQuestionCacheService.commitAnswerAtomically(request);
        switch (commit.getCode()) {
            case COMMITTED -> {
                Integer committedTotal = commit.getTotalScore() == null ? currentTotalScore : commit.getTotalScore();
                ctx.totalScore = committedTotal;
                ctx.turnLog.setTotalScore(committedTotal);
                ctx.response.setTotalScore(committedTotal);
                if (nextFinished) {
                    ctx.response.finish().success();
                } else {
                    ctx.response.withNextQuestion(nextQuestionNumber, nextQuestion, nextIsFollowUp, nextFollowUpCount).success();
                }
                return true;
            }
            case REPLAYED -> {
                // 并发下他方已提交同一 requestId：从提交记录重建响应，不再重复提交。
                InterviewAnswerRespDTO replay = buildReplayResponse(
                        interviewQuestionCacheService.findCommittedAnswerRecord(ctx.sessionId, ctx.requestId));
                if (replay == null) {
                    ctx.response.fail("answer already committed but replay record unavailable");
                    return false;
                }
                ctx.response = replay;
                return false;
            }
            case VERSION_CONFLICT -> {
                Metrics.counter("stale_question_reject_total").increment();
                recordAnswerPipelineFailure("stale_question_reject_atomic_commit");
                ctx.response.fail(STALE_QUESTION_MESSAGE);
                return false;
            }
            default -> {
                // INVALID_ARGUMENT / FINGERPRINT_MISMATCH / CORRUPT_RECORD：fail closed，零副作用。
                recordAnswerPipelineFailure("atomic_commit_" + commit.getCode().name().toLowerCase());
                ctx.response.fail("failed to commit interview answer: " + commit.getCode().name());
                return false;
            }
        }
    }

    private InterviewTurnLog buildTurnLog(
            InterviewAnswerPipelineContext ctx,
            String nextQuestionNumber,
            String nextQuestion,
            boolean finished,
            Integer currentTotalScore) {
        return InterviewTurnLog.builder()
                .timestamp(System.currentTimeMillis())
                .requestId(ctx.requestId)
                .questionNumber(ctx.currentQuestionNumber)
                .questionContent(ctx.currentQuestion)
                .answerContent(truncateForLog(ctx.requestParam.getAnswerContent(), 1000))
                .score(ctx.score)
                .totalScore(currentTotalScore)
                .feedback(ctx.response.getFeedback())
                .followUpNeeded(ctx.followUpNeeded)
                .isFollowUp(ctx.currentIsFollowUp)
                .followUpCount(ctx.currentFollowUpCount)
                .nextQuestionNumber(nextQuestionNumber)
                .nextQuestion(nextQuestion)
                .finished(finished)
                .build();
    }

    private InterviewAnswerRespDTO buildReplayResponse(String recordJson) {
        if (StrUtil.isBlank(recordJson)) {
            return null;
        }
        try {
            JSONObject record = JSON.parseObject(recordJson);
            if (record == null) {
                return null;
            }
            InterviewAnswerRespDTO replay = InterviewAnswerRespDTO.init();
            replay.withNextQuestion(
                    record.getString("nextQuestionNumber"),
                    record.getString("nextQuestion"),
                    Boolean.TRUE.equals(record.getBoolean("isFollowUp")),
                    record.getInteger("followUpCount")
            );
            replay.setScore(record.getInteger("score"));
            replay.setFeedback(record.getString("feedback"));
            replay.setTotalScore(record.getInteger("totalScore"));
            JSONObject turn = record.getJSONObject("turnPayload");
            if (turn != null) {
                replay.withCurrentQuestion(turn.getString("questionNumber"), turn.getString("questionContent"));
            }
            if (Boolean.TRUE.equals(record.getBoolean("finished"))) {
                replay.finish();
            }
            replay.success();
            return replay;
        } catch (Exception ex) {
            log.warn("Failed to build replay response from committed record", ex);
            return null;
        }
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
        private String answerDigest;
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
