package com.hewei.hzyjy.xunzhi.interview.shared;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.AiCallGuardService;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.singleflight.service.DistributedInterviewAiSingleFlightService;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 面试 AI 的统一调用入口，负责生成请求指纹、串联限流熔断保护、
 * 分布式 single-flight 复用以及最终的模型调用过程。
 *
 * @author 程序员牛肉
 */
@Component
@RequiredArgsConstructor
public class InterviewAiInvoker {

    private final XingChenAIClient xingChenAIClient;
    private final AiCallGuardService aiCallGuardService;
    private final DistributedInterviewAiSingleFlightService distributedInterviewAiSingleFlightService;

    public String callAiSync(String prompt, String sessionId, AgentPropertiesDO agentProperties) throws Exception {
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, prompt);
        return callAiSync(prompt, sessionId, agentProperties, InterviewAiGuardStage.INTERVIEW_EVALUATION, key);
    }

    public String callAiSync(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String stage,
            String singleFlightKey) throws Exception {
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, null, null));
    }

    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl) throws Exception {
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_DEMEANOR, sessionId, fileUrl);
        return callAiSyncWithFile(prompt, sessionId, agentProperties, fileUrl, InterviewAiGuardStage.INTERVIEW_DEMEANOR, key);
    }

    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            String stage,
            String singleFlightKey) throws Exception {
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, fileUrl, null));
    }

    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters) throws Exception {
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, input);
        return callAiSyncWithParameters(
                sessionId,
                agentProperties,
                parameters,
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                key
        );
    }

    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters,
            String stage,
            String singleFlightKey) throws Exception {
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        return guardedCall(
                stage,
                singleFlightKey,
                () -> doChat(StrUtil.blankToDefault(input, ""), sessionId, agentProperties, null, parameters)
        );
    }

    public String buildSingleFlightKey(
            String stage,
            String sessionId,
            String questionNumber,
            String answerContent) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeQuestionNumber = StrUtil.blankToDefault(StrUtil.trimToEmpty(questionNumber), "-");
        String safeAnswerHash = StrUtil.isBlank(answerContent)
                ? "-"
                : DigestUtil.sha256Hex(answerContent.trim()).substring(0, 16);
        return safeStage + "|" + safeSessionId + "|" + safeQuestionNumber + "|" + safeAnswerHash;
    }

    public String buildSingleFlightKey(String stage, String sessionId, String businessKey) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeBusinessKey = StrUtil.blankToDefault(StrUtil.trimToEmpty(businessKey), "-");
        return safeStage + "|" + safeSessionId + "|" + safeBusinessKey;
    }

    private String guardedCall(String stage, String singleFlightKey, Callable<String> callable) throws Exception {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String key = StrUtil.blankToDefault(singleFlightKey, safeStage + "|no-key");
        return distributedInterviewAiSingleFlightService.execute(
                safeStage,
                key,
                () -> aiCallGuardService.execute(safeStage, key, callable)
        );
    }

    private String doChat(
            String input,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            Map<String, Object> parameters) throws Exception {
        StringBuilder aiResponse = new StringBuilder();
        // 3) 最后统一走底层 chat，并把流式片段拼成完整响应字符串返回上层解析。
        xingChenAIClient.chat(
                input,
                StrUtil.isNotBlank(sessionId) ? sessionId : "evaluation_" + System.currentTimeMillis(),
                "{}",
                false,
                new OutputStream() {
                    @Override
                    public void write(int b) {
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        aiResponse.append(new String(b, off, len, StandardCharsets.UTF_8));
                    }
                },
                data -> {
                },
                agentProperties.getApiKey(),
                agentProperties.getApiSecret(),
                agentProperties.getApiFlowId(),
                fileUrl,
                parameters
        );
        return aiResponse.toString();
    }
}
