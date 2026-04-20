package com.hewei.hzyjy.xunzhi.interview.application;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.flow.session.InterviewSessionFacade;
import com.hewei.hzyjy.xunzhi.interview.flow.report.InterviewResumePreviewService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRecordService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewSessionFacadeTest {

    @Test
    void shouldRestoreIndependentInterviewSessionState() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-1");
        session.setStatus(InterviewSessionStatus.READY.name());
        session.setResumeFileUrl("https://example.com/new.pdf");
        session.setInterviewType("backend");
        when(sessionService.requireOwnedSession("interview-1", 101L)).thenReturn(session);
        when(cacheService.getSessionInterviewSuggestions("interview-1")).thenReturn(Map.of("1", "Keep answers focused"));
        when(cacheService.getSessionResumeScore("interview-1")).thenReturn(88);

        InterviewQuestion question = new InterviewQuestion();
        question.setResumeScore(66);
        question.setInterviewType("frontend");
        question.setResumeFileUrl("https://example.com/legacy.pdf");
        when(questionService.getBySessionId("interview-1")).thenReturn(question);

        InterviewSessionRestoreRespDTO response = facade.restoreInterviewSession("interview-1", 101L);

        assertEquals("interview-1", response.getSessionId());
        assertEquals(InterviewSessionStatus.READY.name(), response.getStatus());
        assertTrue(response.getCanResume());
        assertEquals("https://example.com/new.pdf", response.getResumeFileUrl());
        assertEquals("backend", response.getInterviewType());
        assertEquals(88, response.getResumeScore());
        assertEquals(Map.of("1", "Keep answers focused"), response.getSuggestions());
        verifyNoInteractions(recordService);
    }

    @Test
    void shouldReturnRadarChartWithoutWritingInterviewRecord() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-2");
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        when(sessionService.requireOwnedSession("interview-2", 202L)).thenReturn(session);

        RadarChartDTO radarChart = new RadarChartDTO();
        radarChart.setInterviewPerformance(85);
        when(cacheService.getRadarChartData("interview-2")).thenReturn(radarChart);

        RadarChartDTO response = facade.getRadarChartData("interview-2", 202L);

        assertSame(radarChart, response);
        verify(sessionService).requireOwnedSession("interview-2", 202L);
        verify(cacheService).getRadarChartData("interview-2");
        verifyNoInteractions(recordService);
    }

    @Test
    void shouldMarkFinishedSessionAsNotResumableDuringRestore() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-3");
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        when(sessionService.requireOwnedSession("interview-3", 303L)).thenReturn(session);
        when(cacheService.getSessionInterviewSuggestions("interview-3")).thenReturn(Map.of());
        when(cacheService.getSessionResumeScore("interview-3")).thenReturn(null);
        when(questionService.getBySessionId("interview-3")).thenReturn(null);

        InterviewSessionRestoreRespDTO response = facade.restoreInterviewSession("interview-3", 303L);

        assertFalse(response.getCanResume());
        assertEquals(InterviewSessionStatus.FINISHED.name(), response.getStatus());
        verifyNoInteractions(recordService);
    }
}
