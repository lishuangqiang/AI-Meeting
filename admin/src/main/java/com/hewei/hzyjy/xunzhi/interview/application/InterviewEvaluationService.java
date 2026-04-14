package com.hewei.hzyjy.xunzhi.interview.application;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.guard.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewEvaluationService {

    private static final String KEY_AGENT_USER_INPUT = "AGENT_USER_INPUT";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_RESUME_CONTEXT = "resume_context";
    private static final String KEY_SCORE = "score";
    private static final String KEY_LOGIC_OK = "logic_ok";
    private static final String KEY_MISSING_POINTS = "missing_points";
    private static final String KEY_FEEDBACK = "feedback";
    private static final String KEY_FOLLOW_UP_NEEDED = "follow_up_needed";
    private static final String KEY_FOLLOW_UP_QUESTION = "follow_up_question";

    private static final String[] RESUME_SUMMARY_KEYS = new String[]{
            "resume_context",
            "resume_summary",
            "resumeSummary",
            "candidate_summary",
            "candidateSummary",
            "profile_summary",
            "profileSummary",
            "summary"
    };

    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;

    /**
     * Evaluate current answer by scorer workflow and return normalized result fields.
     */
    public Map<String, Object> evaluateAnswer(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent,
            AgentPropertiesDO scorerAgent) {

        Map<String, Object> evaluationResult = evaluateAnswerByScorerAgent(
                sessionId, requestId, questionNumber, questionContent, answerContent, scorerAgent);
        //兜底
        if (evaluationResult == null || evaluationResult.isEmpty()) {
            String evaluationPrompt = String.format(
                    "请你作为中文技术面试评审官，对候选人的回答进行打分，并且只返回严格 JSON，不要输出任何额外说明。\n" +
                            "题目：%s\n" +
                            "回答：%s\n" +
                            "要求：\n" +
                            "1. score 为 0-100 的整数；\n" +
                            "2. feedback 必须使用中文，简洁指出优点或问题；\n" +
                            "3. missing_points 必须使用中文数组；\n" +
                            "4. follow_up_needed 为 true/false；\n" +
                            "5. 如果需要追问，follow_up_question 必须使用中文；\n" +
                            "输出格式：{\"score\":0,\"feedback\":\"\",\"follow_up_needed\":true,\"follow_up_question\":\"\",\"missing_points\":[\"...\"]}",
                    questionContent, answerContent
            );
            try {
                String singleFlightKey = interviewAiInvoker.buildSingleFlightKey(
                        InterviewAiGuardStage.INTERVIEW_EVALUATION,
                        sessionId,
                        questionNumber,
                        answerContent
                ) + "|fallback";
                String aiResponse = interviewAiInvoker.callAiSync(
                        evaluationPrompt,
                        sessionId,
                        scorerAgent,
                        InterviewAiGuardStage.INTERVIEW_EVALUATION,
                        singleFlightKey
                );
                evaluationResult = interviewResponseParser.parseEvaluationResult(aiResponse);
            } catch (InterviewAiGuardException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Prompt fallback evaluation failed, sessionId: {}", sessionId, ex);
                return null;
            }
        }
        if (evaluationResult == null) {
            return null;
        }

        Map<String, Object> normalized = normalizeScorerResult(evaluationResult);
        inferFollowUpNeededIfMissing(normalized);
        ensureDefaultEvaluationFields(normalized);
        return normalized;
    }

    private Map<String, Object> evaluateAnswerByScorerAgent(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent,
            AgentPropertiesDO scorerAgent) {
        try {
            String resumeContextText = buildResumeContextText(
                    interviewQuestionCacheService.getSessionResumeContext(sessionId));

            Map<String, Object> parameters = buildScorerWorkflowParameters(
                    answerContent, questionContent, resumeContextText);

            logWorkflowParameters(
                    "scorer",
                    sessionId,
                    requestId,
                    questionNumber,
                    scorerAgent,
                    parameters
            );

            String workflowResponse = interviewAiInvoker.callAiSyncWithParameters(
                    sessionId + "_score",
                    scorerAgent,
                    parameters,
                    InterviewAiGuardStage.INTERVIEW_EVALUATION,
                    interviewAiInvoker.buildSingleFlightKey(
                            InterviewAiGuardStage.INTERVIEW_EVALUATION,
                            sessionId,
                            questionNumber,
                            answerContent
                    )
            );
            Map<String, Object> parsed = interviewResponseParser.parseEvaluationResult(workflowResponse);
            return normalizeScorerResult(parsed);
        } catch (InterviewAiGuardException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Scorer workflow invocation failed, sessionId: {}", sessionId, ex);
            return null;
        }
    }

    private Map<String, Object> buildScorerWorkflowParameters(
            String answerContent,
            String questionContent,
            String resumeContextText) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KEY_AGENT_USER_INPUT, answerContent);
        parameters.put(KEY_QUESTION, questionContent);
        parameters.put(KEY_RESUME_CONTEXT, resumeContextText);
        return parameters;
    }

    private void logWorkflowParameters(
            String stage,
            String sessionId,
            String requestId,
            String questionNumber,
            AgentPropertiesDO agent,
            Map<String, Object> parameters) {
        Map<String, Object> debugView = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (value == null) {
                    return;
                }
                if (KEY_AGENT_USER_INPUT.equals(key) || KEY_RESUME_CONTEXT.equals(key)) {
                    debugView.put(key, clip(String.valueOf(value), 300));
                    return;
                }
                debugView.put(key, value);
            });
        }

        log.info(
                "Workflow request stage={}, sessionId={}, requestId={}, questionNumber={}, flowId={}, params={}",
                stage,
                sessionId,
                requestId,
                questionNumber,
                agent == null ? null : agent.getApiFlowId(),
                JSON.toJSONString(debugView)
        );
    }

    private void inferFollowUpNeededIfMissing(Map<String, Object> result) {
        if (result == null || result.containsKey(KEY_FOLLOW_UP_NEEDED)) {
            return;
        }
        boolean logicOk = interviewResponseParser.asBoolean(result.get(KEY_LOGIC_OK));
        List<String> missingPoints = interviewResponseParser.asStringList(result.get(KEY_MISSING_POINTS));
        String followUpQuestion = interviewResponseParser.asString(result.get(KEY_FOLLOW_UP_QUESTION));
        boolean inferredFollowUp = !logicOk
                || (missingPoints != null && !missingPoints.isEmpty())
                || StrUtil.isNotBlank(followUpQuestion);
        result.put(KEY_FOLLOW_UP_NEEDED, inferredFollowUp);
    }

    private void ensureDefaultEvaluationFields(Map<String, Object> result) {
        if (result == null) {
            return;
        }
        if (!result.containsKey(KEY_MISSING_POINTS)) {
            result.put(KEY_MISSING_POINTS, Collections.emptyList());
        }
        if (!result.containsKey(KEY_FEEDBACK)) {
            result.put(KEY_FEEDBACK, "");
        }
        if (!result.containsKey(KEY_FOLLOW_UP_QUESTION)) {
            result.put(KEY_FOLLOW_UP_QUESTION, "");
        }
    }

    private Map<String, Object> normalizeScorerResult(Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return rawResult;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(rawResult);

        Integer score = extractScoreByKeys(normalized, KEY_SCORE, "total_score", "composite_score");
        if (score != null) {
            normalized.put(KEY_SCORE, score);
        }

        Boolean logicOk = extractBooleanByKeys(normalized, KEY_LOGIC_OK, "logicOk");
        if (logicOk != null) {
            normalized.put(KEY_LOGIC_OK, logicOk);
        }

        List<String> missingPoints = extractStringListByKeys(normalized, KEY_MISSING_POINTS, "missingPoints", "lack_points");
        if (missingPoints != null) {
            normalized.put(KEY_MISSING_POINTS, missingPoints);
        }

        String feedback = extractStringByKeys(normalized, KEY_FEEDBACK, "comment", "suggestion");
        if (feedback != null) {
            normalized.put(KEY_FEEDBACK, feedback);
        }

        Boolean followUpNeeded = extractBooleanByKeys(normalized, KEY_FOLLOW_UP_NEEDED, "followUpNeeded");
        if (followUpNeeded != null) {
            normalized.put(KEY_FOLLOW_UP_NEEDED, followUpNeeded);
        }

        String followUpQuestion = extractStringByKeys(
                normalized, KEY_FOLLOW_UP_QUESTION, "followUpQuestion", "ask_to_user", "ask");
        if (followUpQuestion != null) {
            normalized.put(KEY_FOLLOW_UP_QUESTION, followUpQuestion);
        }

        return normalized;
    }

    private Integer extractScoreByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Integer score = interviewResponseParser.parseScoreFromResponse(source, key);
            if (score != null) {
                return score;
            }
        }
        return null;
    }

    private Boolean extractBooleanByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return interviewResponseParser.asBoolean(source.get(key));
            }
        }
        return null;
    }

    private List<String> extractStringListByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return interviewResponseParser.asStringList(source.get(key));
            }
        }
        return null;
    }

    private String extractStringByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                String value = interviewResponseParser.asString(source.get(key));
                return value == null ? "" : value;
            }
        }
        return null;
    }

    private String buildResumeContextText(Map<String, Object> resumeContext) {
        if (resumeContext == null || resumeContext.isEmpty()) {
            return "";
        }

        String preferredSummary = extractNonBlankStringByKeys(resumeContext, RESUME_SUMMARY_KEYS);
        if (StrUtil.isNotBlank(preferredSummary)) {
            return clip(preferredSummary, 2000);
        }

        Map<String, Object> filteredContext = new LinkedHashMap<>();
        resumeContext.forEach((key, value) -> {
            if (StrUtil.isBlank(key) || value == null) {
                return;
            }
            if ("questions".equals(key)
                    || "suggestions".equals(key)
                    || "sugest".equals(key)
                    || "resumeScore".equals(key)
                    || "score".equals(key)
                    || "type".equals(key)
                    || "interviewType".equals(key)) {
                return;
            }
            filteredContext.put(key, value);
        });

        Map<String, Object> contextToUse = filteredContext.isEmpty() ? resumeContext : filteredContext;
        return clip(JSON.toJSONString(contextToUse), 2000);
    }

    private String extractNonBlankStringByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!source.containsKey(key)) {
                continue;
            }
            String value = interviewResponseParser.asString(source.get(key));
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String clip(String value, int maxLen) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen);
    }
}
