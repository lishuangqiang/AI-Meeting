package com.hewei.hzyjy.xunzhi.interview.flow.session;

import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewRecordRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.application.InterviewWorkflowService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.flow.report.InterviewResumePreviewService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRecordService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.xunzhi.interview.testing.PressureTestReportUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewSessionFacadeConsistencyTest {

    @Test
    void shouldRestoreSessionDataFromDatabaseWhenCacheMissed() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = mock(InterviewSessionRuntimeSnapshotService.class);
        InterviewSessionRuntimeRehydrateService runtimeRehydrateService = mock(InterviewSessionRuntimeRehydrateService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService,
                runtimeSnapshotService,
                runtimeRehydrateService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("session-consistency-1");
        session.setStatus(InterviewSessionStatus.READY.name());
        session.setResumeFileUrl(null);
        session.setInterviewType(null);
        when(sessionService.requireOwnedSession("session-consistency-1", 9001L)).thenReturn(session);

        InterviewQuestion question = new InterviewQuestion();
        question.setResumeFileUrl("https://db/resume.pdf");
        question.setInterviewType("backend");
        question.setResumeScore(86);
        when(questionService.getBySessionId("session-consistency-1")).thenReturn(question);

        when(cacheService.getSessionInterviewSuggestions("session-consistency-1"))
                .thenReturn(Map.of(), Map.of("1", "db-suggestion"));
        when(cacheService.getSessionResumeScore("session-consistency-1"))
                .thenReturn(null, 86);

        InterviewSessionRestoreRespDTO response = facade.restoreInterviewSession("session-consistency-1", 9001L);

        assertEquals("https://db/resume.pdf", response.getResumeFileUrl());
        assertEquals("backend", response.getInterviewType());
        assertEquals(86, response.getResumeScore());
        assertEquals(Map.of("1", "db-suggestion"), response.getSuggestions());
        verify(cacheService, times(1)).loadInterviewSuggestionsFromDatabase("session-consistency-1");
        verify(cacheService, times(1)).loadResumeScoreFromDatabase("session-consistency-1");
    }

    @Test
    void shouldFallbackToRecordScoreWhenCacheScoreMissingOrNonPositive() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = mock(InterviewSessionRuntimeSnapshotService.class);
        InterviewSessionRuntimeRehydrateService runtimeRehydrateService = mock(InterviewSessionRuntimeRehydrateService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService,
                runtimeSnapshotService,
                runtimeRehydrateService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("session-consistency-2");
        session.setStatus(InterviewSessionStatus.IN_PROGRESS.name());
        when(sessionService.requireOwnedSession("session-consistency-2", 9002L)).thenReturn(session);
        when(cacheService.getSessionTotalScore("session-consistency-2")).thenReturn(0);

        InterviewRecordRespDTO recordResp = new InterviewRecordRespDTO();
        recordResp.setInterviewScore(78);
        when(recordService.getBySessionId("session-consistency-2", 9002L)).thenReturn(recordResp);

        Integer score = facade.getSessionTotalScore("session-consistency-2", 9002L);
        assertEquals(78, score);
    }

    @Test
    void shouldFallbackToRecordRadarWhenCacheRadarHasNoSignal() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = mock(InterviewSessionRuntimeSnapshotService.class);
        InterviewSessionRuntimeRehydrateService runtimeRehydrateService = mock(InterviewSessionRuntimeRehydrateService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService,
                runtimeSnapshotService,
                runtimeRehydrateService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("session-consistency-3");
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        when(sessionService.requireOwnedSession("session-consistency-3", 9003L)).thenReturn(session);

        RadarChartDTO emptyRadar = new RadarChartDTO();
        when(cacheService.getRadarChartData("session-consistency-3")).thenReturn(emptyRadar);

        RadarChartDTO snapshotRadar = new RadarChartDTO();
        snapshotRadar.setPotentialIndex(82);
        InterviewRecordRespDTO recordResp = new InterviewRecordRespDTO();
        recordResp.setRadarChart(snapshotRadar);
        when(recordService.getBySessionId("session-consistency-3", 9003L)).thenReturn(recordResp);

        RadarChartDTO actual = facade.getRadarChartData("session-consistency-3", 9003L);
        assertSame(snapshotRadar, actual);
    }

    @Test
    void shouldKeepConcurrentScoreReadsConsistentWhenCacheMissed() throws Exception {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewQuestionCacheService cacheService = mock(InterviewQuestionCacheService.class);
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = mock(InterviewSessionRuntimeSnapshotService.class);
        InterviewSessionRuntimeRehydrateService runtimeRehydrateService = mock(InterviewSessionRuntimeRehydrateService.class);
        InterviewSessionFacade facade = new InterviewSessionFacade(
                workflowService,
                cacheService,
                questionService,
                recordService,
                previewService,
                sessionService,
                runtimeSnapshotService,
                runtimeRehydrateService
        );

        InterviewSession session = new InterviewSession();
        session.setSessionId("session-consistency-4");
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        when(sessionService.requireOwnedSession("session-consistency-4", 9004L)).thenReturn(session);
        when(cacheService.getSessionTotalScore("session-consistency-4")).thenReturn(0);

        InterviewRecordRespDTO recordResp = new InterviewRecordRespDTO();
        recordResp.setInterviewScore(81);
        when(recordService.getBySessionId("session-consistency-4", 9004L)).thenReturn(recordResp);

        int concurrency = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ScoreTaskResult>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Callable<ScoreTaskResult> task = () -> {
                start.await(2, TimeUnit.SECONDS);
                long begin = System.nanoTime();
                Integer score = facade.getSessionTotalScore("session-consistency-4", 9004L);
                return new ScoreTaskResult(score, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        List<Long> latencies = new ArrayList<>();
        for (Future<ScoreTaskResult> future : futures) {
            ScoreTaskResult taskResult = future.get(5, TimeUnit.SECONDS);
            latencies.add(taskResult.latencyMs());
            Integer score = taskResult.score();
            assertNotNull(score);
            assertEquals(81, score);
        }
        pool.shutdownNow();
        PressureTestReportUtil.printSummary(
                "facade.get-score.concurrent-cache-miss-fallback",
                concurrency,
                concurrency,
                0,
                latencies,
                Map.of("sessionId", "session-consistency-4", "expectedScore", 81)
        );
        verify(recordService, times(concurrency)).getBySessionId("session-consistency-4", 9004L);
    }

    private record ScoreTaskResult(Integer score, long latencyMs) {
    }
}
