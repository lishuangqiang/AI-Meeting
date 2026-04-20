package com.hewei.hzyjy.xunzhi.interview.application;

import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.xunzhi.interview.flow.extraction.InterviewQuestionExtractionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewQuestionExtractionServiceTest {

    @Test
    void shouldFailWhenWorkflowFallsBackToSmallTalkInsteadOfQuestions() throws Exception {
        BusinessAgentResolver businessAgentResolver = mock(BusinessAgentResolver.class);
        XingChenAIClient xingChenAIClient = mock(XingChenAIClient.class);
        InterviewAiInvoker interviewAiInvoker = mock(InterviewAiInvoker.class);
        InterviewAiSessionLockService interviewAiSessionLockService = mock(InterviewAiSessionLockService.class);
        InterviewQuestionService interviewQuestionService = mock(InterviewQuestionService.class);
        InterviewQuestionCacheService interviewQuestionCacheService = mock(InterviewQuestionCacheService.class);
        InterviewResponseParser interviewResponseParser = new InterviewResponseParser();
        InterviewQuestionExtractionService service = new InterviewQuestionExtractionService(
                businessAgentResolver,
                xingChenAIClient,
                interviewAiInvoker,
                interviewAiSessionLockService,
                interviewQuestionService,
                interviewQuestionCacheService,
                interviewResponseParser
        );

        AgentPropertiesDO agent = new AgentPropertiesDO();
        agent.setId(8L);
        agent.setApiKey("key");
        agent.setApiSecret("secret");
        agent.setApiFlowId("flow-id");
        when(businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_EXTRACTION))
                .thenReturn(agent);
        when(xingChenAIClient.uploadFile(any(), eq("key"), eq("secret")))
                .thenReturn("https://example.com/resume.pdf");

        RLock heavyLock = mock(RLock.class);
        when(interviewAiSessionLockService.acquire("session-1", InterviewAiGuardStage.INTERVIEW_EXTRACTION)).thenReturn(heavyLock);
        String workflowResponse = """
                {"choices":[{"delta":{"role":"assistant","content":"{\\"questions\\":[],\\"sugest\\":[],\\"type\\":\\"\\",\\"smallTalk\\":\\"fallback smalltalk\\",\\"resumeScore\\":0}"}}]}
                """;
        when(interviewAiInvoker.callAiSyncWithFile(
                any(),
                eq("session-1"),
                eq(agent),
                eq("https://example.com/resume.pdf"),
                eq(InterviewAiGuardStage.INTERVIEW_EXTRACTION),
                any()
        )).thenReturn(workflowResponse);

        InterviewQuestionReqDTO request = new InterviewQuestionReqDTO();
        request.setSessionId("session-1");
        request.setUserName("tester");
        request.setResumePdf(new MockMultipartFile(
                "resumePdf",
                "resume.pdf",
                "application/pdf",
                "dummy".getBytes(StandardCharsets.UTF_8)
        ));

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request);

        assertEquals(0, response.getIsSuccess());
        assertTrue(response.getErrorMessage().contains("smallTalk"));
        verify(interviewQuestionService).createFromAIResponse(
                eq(request),
                any(),
                any(),
                eq(null)
        );
        verify(interviewQuestionCacheService, never()).cacheInterviewQuestions(eq("session-1"), any());
        verify(interviewQuestionCacheService, never()).initInterviewFlow(eq("session-1"), any());
        verify(interviewAiSessionLockService).release(heavyLock);
    }
}
