package com.hewei.hzyjy.xunzhi.interview.application;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.DemeanorNormalizationStrategy;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
/**
 * Demeanor scoring service.
 * Responsible for image upload, workflow invocation, response parsing, and cache persistence.
 */
public class InterviewDemeanorService {

    private static final String KEY_PANIC_LEVEL = "panicLevel";
    private static final String KEY_SERIOUSNESS_LEVEL = "seriousnessLevel";
    private static final String KEY_EMOTICON_HANDLING = "emoticonHandling";
    private static final String KEY_COMPOSITE_SCORE = "compositeScore";

    private final XingChenAIClient xingChenAIClient;
    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;
    private final DemeanorNormalizationStrategy demeanorNormalizationStrategy;

    public String evaluateDemeanor(DemeanorEvaluationReqDTO reqDTO) {
        String sessionId = null;
        try {
            String imageUrl = null;

            // Upload image first and get a workflow-readable URL.
            if (reqDTO.getUserPhoto() != null && !reqDTO.getUserPhoto().isEmpty()) {
                try {
                    AgentPropertiesDO agentProperties = resolveRequiredAgent(reqDTO);
                    if (agentProperties == null) {
                        throw new ClientException(InterviewErrorCodeEnum.AGENT_CONFIG_NOT_FOUND);
                    }

                    imageUrl = xingChenAIClient.uploadFile(
                            reqDTO.getUserPhoto(),
                            agentProperties.getApiKey(),
                            agentProperties.getApiSecret()
                    );
                    log.info("Image uploaded successfully, URL: {}", imageUrl);
                } catch (ClientException ce) {
                    throw ce;
                } catch (Exception e) {
                    log.error("Image upload failed: {}", e.getMessage());
                    throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_FILE_UPLOAD_FAILED);
                }
            }

            if (imageUrl == null) {
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_USER_PHOTO_NOT_FOUND);
            }

            String promptBuilder = "Evaluate this photo and return integer scores (0-100) for panicLevel, seriousnessLevel, emoticonHandling and compositeScore.";
            AgentPropertiesDO agentProperties = resolveRequiredAgent(reqDTO);
            if (agentProperties == null) {
                throw new ClientException(InterviewErrorCodeEnum.AGENT_CONFIG_NOT_FOUND);
            }

            // Invoke demeanor workflow.
            String aiResponseStr = interviewAiInvoker.callAiSyncWithFile(
                    promptBuilder,
                    reqDTO.getSessionId() != null ? reqDTO.getSessionId() : "demeanor_" + System.currentTimeMillis(),
                    agentProperties,
                    imageUrl
            );

            log.info("Raw demeanor response: {}", aiResponseStr);
            sessionId = reqDTO.getSessionId();
            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(aiResponseStr);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                log.error("Demeanor workflow failed, sessionId={}, {}", sessionId, workflowErrorMessage);
                throw new ClientException(workflowErrorMessage, InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_INVALID);
            }
            try {
                Map<String, Object> contentMap = interviewResponseParser.extractStructuredResult(
                        aiResponseStr,
                        KEY_PANIC_LEVEL,
                        KEY_SERIOUSNESS_LEVEL,
                        KEY_EMOTICON_HANDLING,
                        KEY_COMPOSITE_SCORE
                );
                log.info("Parsed demeanor content map: {}", contentMap);

                if (contentMap == null || contentMap.isEmpty()) {
                    log.error("Missing structured result in demeanor response");
                    throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_FORMAT_ERROR);
                }

                Integer panicLevel = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_PANIC_LEVEL);
                Integer seriousnessLevel = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_SERIOUSNESS_LEVEL);
                Integer emoticonHandling = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_EMOTICON_HANDLING);
                Integer compositeScore = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_COMPOSITE_SCORE);

                if (panicLevel != null && seriousnessLevel != null
                        && emoticonHandling != null && compositeScore != null) {
                    boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(
                            panicLevel,
                            seriousnessLevel,
                            emoticonHandling,
                            compositeScore
                    );
                    int normalizedPanic = demeanorNormalizationStrategy.normalize(panicLevel, tenScaleDetected);
                    int normalizedSeriousness = demeanorNormalizationStrategy.normalize(seriousnessLevel, tenScaleDetected);
                    int normalizedEmoticon = demeanorNormalizationStrategy.normalize(emoticonHandling, tenScaleDetected);
                    int normalizedComposite = demeanorNormalizationStrategy.normalize(compositeScore, tenScaleDetected);

                    if (tenScaleDetected) {
                        log.info("Demeanor score detected as 0-10 scale, converted to 0-100, sessionId={}", sessionId);
                    }

                    interviewQuestionCacheService.cacheDemeanorScoreDetails(
                            sessionId, normalizedPanic, normalizedSeriousness, normalizedEmoticon, normalizedComposite
                    );
                    interviewQuestionCacheService.cacheDemeanorScore(sessionId, normalizedComposite);
                    log.info("Demeanor score success, sessionId={}, panic={}, seriousness={}, emoticon={}, composite={}",
                            sessionId, normalizedPanic, normalizedSeriousness, normalizedEmoticon, normalizedComposite);
                    return "Demeanor evaluation completed";
                }

                log.error("Invalid score fields: panicLevel={}, seriousnessLevel={}, emoticonHandling={}, compositeScore={}",
                        panicLevel, seriousnessLevel, emoticonHandling, compositeScore);
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_INVALID);
            } catch (ClientException ce) {
                throw ce;
            } catch (Exception parseException) {
                log.error("Failed to parse demeanor response, raw={}, error={}",
                        aiResponseStr, parseException.getMessage(), parseException);
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_PARSE_ERROR);
            }

        } catch (ClientException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Demeanor evaluation failed, sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_EVALUATION_FAILED);
        }
    }

    private AgentPropertiesDO resolveRequiredAgent(DemeanorEvaluationReqDTO reqDTO) {
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_DEMEANOR);
        reqDTO.setAgentId(agentProperties.getId());
        return agentProperties;
    }

}
