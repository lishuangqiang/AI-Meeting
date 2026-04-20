package com.hewei.hzyjy.xunzhi.interview.flow.demeanor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardErrorCode;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.xunzhi.interview.application.strategy.DemeanorNormalizationStrategy;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
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
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    private final InterviewResponseParser interviewResponseParser;
    private final DemeanorNormalizationStrategy demeanorNormalizationStrategy;

    public String evaluateDemeanor(DemeanorEvaluationReqDTO reqDTO) {
        String sessionId = null;
        RLock heavyLock = null;
        try {
            // 1) 先抢会话级重锁，避免同一 session 并发神态评估互相覆盖。
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_DEMEANOR);
            if (heavyLock == null) {
                throw new ClientException(
                        "AI_OVERLOADED: demeanor evaluation is processing, please retry",
                        InterviewErrorCodeEnum.AI_OVERLOADED
                );
            }

            String imageUrl = null;

            // Upload image first and get a workflow-readable URL.
            if (reqDTO.getUserPhoto() != null && !reqDTO.getUserPhoto().isEmpty()) {
                try {
                    // 2) 上传照片，拿到 AI workflow 可访问的文件 URL。
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

            // 3) 调用神态工作流并解析结构化分值，最后统一归一化后落缓存。
            String aiResponseStr = interviewAiInvoker.callAiSyncWithFile(
                    promptBuilder,
                    reqDTO.getSessionId() != null ? reqDTO.getSessionId() : "demeanor_" + System.currentTimeMillis(),
                    agentProperties,
                    imageUrl,
                    InterviewAiGuardStage.INTERVIEW_DEMEANOR,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_DEMEANOR, reqDTO.getSessionId(), imageUrl)
            );

            log.info("Demeanor response received, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    aiResponseStr == null ? 0 : aiResponseStr.length(),
                    digestForLog(aiResponseStr));
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
                log.info("Parsed demeanor content keys: {}", contentMap == null ? null : contentMap.keySet());

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
                log.error("Failed to parse demeanor response, sessionId={}, payloadHash={}, error={}",
                        sessionId, digestForLog(aiResponseStr), parseException.getMessage(), parseException);
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_PARSE_ERROR);
            }

        } catch (ClientException ce) {
            throw ce;
        } catch (InterviewAiGuardException aiGuardException) {
            throw new ClientException(aiGuardException.getMessage(), mapAiGuardError(aiGuardException.getErrorCode()));
        } catch (Exception e) {
            log.error("Demeanor evaluation failed, sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_EVALUATION_FAILED);
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    private String digestForLog(String payload) {
        if (StrUtil.isBlank(payload)) {
            return "-";
        }
        return DigestUtil.sha256Hex(payload).substring(0, 16);
    }

    private AgentPropertiesDO resolveRequiredAgent(DemeanorEvaluationReqDTO reqDTO) {
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_DEMEANOR);
        reqDTO.setAgentId(agentProperties.getId());
        return agentProperties;
    }

    private InterviewErrorCodeEnum mapAiGuardError(InterviewAiGuardErrorCode errorCode) {
        if (errorCode == null) {
            return InterviewErrorCodeEnum.AI_UNAVAILABLE;
        }
        return switch (errorCode) {
            case AI_TIMEOUT -> InterviewErrorCodeEnum.AI_TIMEOUT;
            case AI_OVERLOADED -> InterviewErrorCodeEnum.AI_OVERLOADED;
            default -> InterviewErrorCodeEnum.AI_UNAVAILABLE;
        };
    }

}
