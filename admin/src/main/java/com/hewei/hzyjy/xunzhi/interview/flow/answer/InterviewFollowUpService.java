package com.hewei.hzyjy.xunzhi.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpService {

    private static final String KEY_AGENT_USER_INPUT = "AGENT_USER_INPUT";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FOLLOW_UP_COUNT = "follow_up_count";
    private static final String KEY_MAX_FOLLOW_UP = "max_follow_up";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_RESUME_CONTEXT = "resume_context";
    private static final String KEY_ASK_TO_USER = "ask_to_user";
    private static final String KEY_END_INTERVIEW = "end_interview";

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;

    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId,
            String requestId,
            String currentQuestionNumber,
            String currentQuestion,
            String answerContent,
            String fallbackFollowUpQuestion,
            Integer currentFollowUpCount,
            Integer maxFollowUp) {

        // 1) 先做追问次数与输入兜底，超过上限直接停止追问。
        int safeCurrentFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 2 : maxFollowUp;
        String sanitizedFallbackQuestion = sanitizeFollowUpQuestion(fallbackFollowUpQuestion);
        if (safeCurrentFollowUpCount >= safeMaxFollowUp) {
            return FollowUpQuestionResult.empty();
        }

        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeCurrentFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) {
            return FollowUpQuestionResult.empty();
        }

        String generatedQuestion = null;
        try {
            // 2) 优先调用追问工作流生成更针对性的追问。
            AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING);
            generatedQuestion = invokeFollowUpWorkflow(
                    sessionId,
                    requestId,
                    currentQuestion,
                    answerContent,
                    safeCurrentFollowUpCount,
                    safeMaxFollowUp,
                    agentProperties
            );
        } catch (Exception ex) {
            log.warn("Follow-up agent unavailable, fallback to scorer suggestion, sessionId={}", sessionId, ex);
        }

        // 3) 工作流失败时回退到评分器建议问题，仍可继续流程。
        String questionContent = StrUtil.isNotBlank(generatedQuestion) ? generatedQuestion : sanitizedFallbackQuestion;
        if (StrUtil.isBlank(questionContent)) {
            return FollowUpQuestionResult.empty();
        }

        return new FollowUpQuestionResult(questionNumber, questionContent, safeCurrentFollowUpCount + 1);
    }

    private String invokeFollowUpWorkflow(
            String sessionId,
            String requestId,
            String currentQuestion,
            String answerContent,
            int currentFollowUpCount,
            int maxFollowUp,
            AgentPropertiesDO agentProperties) {

        if (agentProperties == null || StrUtil.isBlank(currentQuestion) || StrUtil.isBlank(answerContent)) {
            return null;
        }

        try {
            Map<String, Object> parameters = buildWorkflowParameters(
                    answerContent,
                    currentQuestion,
                    currentFollowUpCount,
                    maxFollowUp,
                    buildResumeContextText(interviewQuestionCacheService.getSessionResumeContext(sessionId))
            );
            log.info(
                    "Follow-up workflow request, sessionId={}, requestId={}, question={}, followUpCount={}, maxFollowUp={}",
                    sessionId,
                    requestId,
                    clip(currentQuestion, 120),
                    currentFollowUpCount,
                    maxFollowUp
            );
            String workflowResponse;
            String singleFlightKey = interviewAiInvoker.buildSingleFlightKey(
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    sessionId,
                    currentQuestion,
                    answerContent
            );
            workflowResponse = interviewAiInvoker.callAiSyncWithParameters(
                    sessionId,
                    agentProperties,
                    parameters,
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    singleFlightKey
            );
            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(workflowResponse);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                log.warn("Follow-up workflow returned error, sessionId={}, message={}", sessionId, workflowErrorMessage);
                return null;
            }

            Map<String, Object> workflowResult = interviewResponseParser.extractStructuredResult(
                    workflowResponse,
                    KEY_ASK_TO_USER,
                    KEY_END_INTERVIEW
            );
            if (workflowResult != null && interviewResponseParser.asBoolean(workflowResult.get(KEY_END_INTERVIEW))) {
                return null;
            }

            String askToUser = workflowResult == null ? null : interviewResponseParser.asString(workflowResult.get(KEY_ASK_TO_USER));
            if (StrUtil.isBlank(askToUser)) {
                askToUser = interviewResponseParser.extractContentFromInterviewResponse(workflowResponse);
            }
            return sanitizeFollowUpQuestion(askToUser);
        } catch (InterviewAiGuardException ex) {
            log.warn("Follow-up workflow fast-failed, sessionId={}, code={}", sessionId, ex.getErrorCode());
            return null;
        } catch (Exception ex) {
            log.warn("Failed to invoke follow-up workflow, sessionId={}", sessionId, ex);
            return null;
        }
    }

    private Map<String, Object> buildWorkflowParameters(
            String answerContent,
            String currentQuestion,
            int currentFollowUpCount,
            int maxFollowUp,
            String resumeContextText) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KEY_AGENT_USER_INPUT, answerContent);
        parameters.put(KEY_MODE, "FOLLOW_UP");
        parameters.put(KEY_FOLLOW_UP_COUNT, currentFollowUpCount);
        parameters.put(KEY_MAX_FOLLOW_UP, maxFollowUp);
        parameters.put(KEY_QUESTION, currentQuestion);
        parameters.put(KEY_RESUME_CONTEXT, resumeContextText);
        return parameters;
    }

    private String buildResumeContextText(Map<String, Object> resumeContext) {
        if (resumeContext == null || resumeContext.isEmpty()) {
            return "";
        }
        return clip(JSON.toJSONString(resumeContext), 2000);
    }

    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (!normalized.endsWith("?")) {
            normalized = normalized + "?";
        }
        return clip(normalized, 100);
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

    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) {
            return null;
        }
        return mainQuestionNumber + "-F" + followUpCount;
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @Getter
    public static final class FollowUpQuestionResult {
        private final String questionNumber;
        private final String questionContent;
        private final Integer followUpCount;

        private FollowUpQuestionResult(String questionNumber, String questionContent, Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() {
            return new FollowUpQuestionResult(null, null, 0);
        }

        public boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}


