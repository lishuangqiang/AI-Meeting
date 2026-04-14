package com.hewei.hzyjy.xunzhi.interview.application;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.InterviewAiSessionLockService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionExtractionService {

    private static final String EXTRACTION_PROMPT =
            "Extract technical interview questions from the uploaded resume. "
                    + "Return JSON only with keys questions, sugest, type, and resumeScore. "
                    + "Do not output smallTalk, greetings, or fallback chat content.";

    private final BusinessAgentResolver businessAgentResolver;
    private final XingChenAIClient xingChenAIClient;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewResponseParser interviewResponseParser;

    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());

        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(
                BusinessAgentScene.INTERVIEW_QUESTION_EXTRACTION);
        reqDTO.setAgentId(agentProperties.getId());
        response.setIsSuccess(0);

        RLock heavyLock = null;
        long startTime = System.currentTimeMillis();
        try {
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            String fileUrl = uploadResumeIfPresent(reqDTO, agentProperties, response);
            if (fileUrl == null) {
                return response;
            }

            String fullContent = interviewAiInvoker.callAiSyncWithFile(
                    EXTRACTION_PROMPT,
                    reqDTO.getSessionId(),
                    agentProperties,
                    fileUrl,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EXTRACTION, reqDTO.getSessionId(), fileUrl)
            );

            long responseTime = System.currentTimeMillis() - startTime;
            reqDTO.setResumeFileUrl(fileUrl);

            persistRawResponse(reqDTO, fullContent, responseTime);

            response.setResumeFileUrl(fileUrl);
            response.setResponseTime((int) responseTime);

            if (!populateStructuredResponse(reqDTO, response, fullContent)) {
                return response;
            }

            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}", reqDTO.getSessionId());
            return response;
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed: {}", e.getMessage(), e);
            try {
                reqDTO.setResumeFileUrl(null);
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction error record: {}", saveException.getMessage());
            }

            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    private String uploadResumeIfPresent(
            InterviewQuestionReqDTO reqDTO,
            AgentPropertiesDO agentProperties,
            InterviewQuestionRespDTO response) {
        if (reqDTO.getResumePdf() == null || reqDTO.getResumePdf().isEmpty()) {
            response.setErrorMessage("resume file does not exist");
            return null;
        }
        try {
            String fileUrl = xingChenAIClient.uploadFile(
                    reqDTO.getResumePdf(),
                    agentProperties.getApiKey(),
                    agentProperties.getApiSecret()
            );
            log.info("Resume uploaded successfully, url={}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("Resume upload failed: {}", e.getMessage());
            response.setErrorMessage("failed to upload resume file");
            return null;
        }
    }

    private void persistRawResponse(InterviewQuestionReqDTO reqDTO, String fullContent, long responseTime) {
        try {
            interviewQuestionService.createFromAIResponse(
                    reqDTO,
                    fullContent,
                    (int) responseTime,
                    null
            );
            log.info("Interview question response saved, sessionId={}", reqDTO.getSessionId());
        } catch (Exception e) {
            log.error("Failed to save interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage());
        }
    }

    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent) {
        try {
            log.info("Start parsing interview question response, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    fullContent == null ? 0 : fullContent.length(),
                    digestForLog(fullContent));

            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(fullContent);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                response.setErrorMessage(workflowErrorMessage);
                log.warn("Interview question workflow returned error, sessionId={}, message={}",
                        reqDTO.getSessionId(), workflowErrorMessage);
                return false;
            }

            String extractedContent = interviewResponseParser.extractContentFromInterviewResponse(fullContent);
            log.info("Extracted interview content summary, sessionId={}, contentLength={}, contentHash={}",
                    reqDTO.getSessionId(),
                    extractedContent == null ? 0 : extractedContent.length(),
                    digestForLog(extractedContent));
            if (StrUtil.isBlank(extractedContent)) {
                response.setErrorMessage("interview question response content is blank");
                return false;
            }

            Map<String, Object> responseMap = interviewResponseParser.extractStructuredResult(
                    extractedContent,
                    "questions",
                    "sugest",
                    "suggestions",
                    "resumeScore",
                    "type",
                    "smallTalk"
            );
            if (responseMap == null || responseMap.isEmpty()) {
                response.setErrorMessage("interview question response parse failed");
                log.warn("Interview question response parse failed, responseMap is null");
                return false;
            }

            log.info("Interview question response fields: {}", responseMap.keySet());
            Map<String, Object> resumeContext = buildResumeContext(responseMap);
            if (!resumeContext.isEmpty()) {
                interviewQuestionCacheService.cacheResumeContext(reqDTO.getSessionId(), resumeContext);
            }

            List<String> questions = normalizeStringList(responseMap.get("questions"));
            if (questions.isEmpty()) {
                String smallTalk = interviewResponseParser.asString(responseMap.get("smallTalk"));
                response.setErrorMessage(StrUtil.isNotBlank(smallTalk)
                        ? "workflow fell back to smallTalk instead of interview questions"
                        : "workflow returned empty interview questions");
                log.warn("Interview question extraction returned no questions, sessionId={}, smallTalk={}",
                        reqDTO.getSessionId(), smallTalk);
                return false;
            }

            interviewQuestionCacheService.cacheInterviewQuestions(reqDTO.getSessionId(), questions);
            Map<String, String> questionMap =
                    interviewQuestionCacheService.getSessionInterviewQuestions(reqDTO.getSessionId());
            response.setQuestions(questionMap);
            response.setQuestionCount(questions.size());
            interviewQuestionCacheService.initInterviewFlow(reqDTO.getSessionId(), questions.size());

            List<String> suggestions = normalizeSuggestions(responseMap);
            if (!suggestions.isEmpty()) {
                interviewQuestionCacheService.cacheInterviewSuggestions(reqDTO.getSessionId(), suggestions);
                Map<String, String> suggestionMap =
                        interviewQuestionCacheService.getSessionInterviewSuggestions(reqDTO.getSessionId());
                response.setSuggestions(suggestionMap);
                response.setSuggestionCount(suggestions.size());
            } else {
                log.warn("Interview question response does not contain suggestions");
            }

            String interviewType = interviewResponseParser.asString(responseMap.get("type"));
            if (StrUtil.isNotBlank(interviewType)) {
                interviewQuestionCacheService.cacheInterviewDirection(reqDTO.getSessionId(), interviewType);
                response.setInterviewType(interviewType);
            } else {
                log.warn("Interview question response does not contain type field");
            }

            Integer resumeScore = interviewResponseParser.parseScoreFromResponse(responseMap, "resumeScore");
            if (resumeScore != null) {
                interviewQuestionCacheService.cacheResumeScore(reqDTO.getSessionId(), resumeScore);
                response.setResumeScore(resumeScore);
            } else {
                log.warn("Interview question response does not contain valid resumeScore field");
            }

            interviewQuestionCacheService.resetSessionScore(reqDTO.getSessionId());
            log.info("Session score reset, sessionId={}", reqDTO.getSessionId());
            return true;
        } catch (Exception cacheException) {
            response.setErrorMessage("failed to parse interview question response");
            log.error(
                    "Failed to cache interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(),
                    cacheException.getMessage()
            );
            return false;
        }
    }

    private List<String> normalizeSuggestions(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> suggestions = normalizeStringList(responseMap.get("sugest"));
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        return normalizeStringList(responseMap.get("suggestions"));
    }

    private List<String> normalizeStringList(Object value) {
        return interviewResponseParser.asStringList(value);
    }

    private String digestForLog(String value) {
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        return DigestUtil.sha256Hex(value).substring(0, 16);
    }

    private Map<String, Object> buildResumeContext(Map<String, Object> responseMap) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (responseMap == null || responseMap.isEmpty()) {
            return context;
        }
        for (Map.Entry<String, Object> entry : responseMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("questions".equals(key) || "sugest".equals(key) || "suggestions".equals(key)) {
                continue;
            }
            context.put(key, value);
        }
        return context;
    }
}
