package com.hewei.hzyjy.xunzhi.interview.flow.session;

import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.InterviewWorkflowService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.hewei.hzyjy.xunzhi.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.flow.report.InterviewResumePreviewService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRecordService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.xunzhi.interview.testing.PressureTestReportUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewSessionFacadePressureTest {

    @Test
    void shouldKeepAnswerResponsesConsistentUnderConcurrentMockWorkflow() throws Exception {
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
        session.setSessionId("session-pressure-1");
        session.setStatus(InterviewSessionStatus.READY.name());
        when(sessionService.requireOwnedSession("session-pressure-1", 1001L)).thenReturn(session);
        doNothing().when(sessionService).markInProgressIfReady("session-pressure-1", 1001L);

        when(workflowService.answerInterviewQuestion(anyString(), any(InterviewAnswerReqDTO.class)))
                .thenAnswer(invocation -> {
                    InterviewAnswerReqDTO req = invocation.getArgument(1);
                    return InterviewAnswerRespDTO.init()
                            .withCurrentQuestion(req.getQuestionNumber(), "Q-" + req.getQuestionNumber())
                            .withEvaluation(85, "ok", 85)
                            .withNextQuestion("2", "next-q", false, 0)
                            .success();
                });

        int concurrency = 48;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AnswerTaskResult>> futures = new ArrayList<>();
        List<InterviewAnswerReqDTO> requests = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            InterviewAnswerReqDTO req = new InterviewAnswerReqDTO();
            req.setQuestionNumber("1");
            req.setAnswerContent("answer-" + i);
            req.setRequestId("rid-" + i);
            requests.add(req);
            futures.add(pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                long begin = System.nanoTime();
                InterviewAnswerRespDTO response = facade.answerInterviewQuestion("session-pressure-1", req, 1001L);
                return new AnswerTaskResult(response, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
            }));
        }

        start.countDown();

        int successCount = 0;
        List<Long> latencies = new ArrayList<>();
        for (Future<AnswerTaskResult> future : futures) {
            AnswerTaskResult taskResult = future.get(5, TimeUnit.SECONDS);
            latencies.add(taskResult.latencyMs());
            InterviewAnswerRespDTO resp = taskResult.response();
            assertTrue(Boolean.TRUE.equals(resp.getIsSuccess()));
            assertEquals("1", resp.getQuestionNumber());
            assertEquals("Q-1", resp.getQuestionContent());
            successCount++;
        }
        pool.shutdownNow();
        PressureTestReportUtil.printSummary(
                "facade.answer.concurrent-mock-workflow",
                concurrency,
                successCount,
                concurrency - successCount,
                latencies,
                Map.of("sessionId", "session-pressure-1")
        );

        assertEquals(concurrency, successCount);
        for (InterviewAnswerReqDTO request : requests) {
            assertEquals("session-pressure-1", request.getSessionId());
        }
        verify(workflowService, times(concurrency)).answerInterviewQuestion(anyString(), any(InterviewAnswerReqDTO.class));
    }

    @Test
    void shouldKeepReadyDraftTransitionsConsistentUnderConcurrentMockExtraction() throws Exception {
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

        doNothing().when(sessionService).markResumeUploading(anyString(), anyLong());
        doNothing().when(sessionService).markReady(anyString(), anyLong(), anyString(), anyString());
        doNothing().when(sessionService).markDraft(anyString(), anyLong());

        AtomicInteger callSeq = new AtomicInteger(0);
        when(workflowService.extractInterviewQuestions(any(InterviewQuestionReqDTO.class)))
                .thenAnswer(invocation -> {
                    int index = callSeq.incrementAndGet();
                    InterviewQuestionRespDTO resp = new InterviewQuestionRespDTO();
                    resp.setSessionId("session-pressure-2");
                    resp.setUserName("tester");
                    if (index % 2 == 0) {
                        resp.setIsSuccess(0);
                        resp.setErrorMessage("mock-fail");
                        return resp;
                    }
                    resp.setIsSuccess(1);
                    resp.setResumeFileUrl("https://mock/resume-" + index + ".pdf");
                    resp.setInterviewType("backend");
                    return resp;
                });

        int concurrency = 40;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExtractionTaskResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                MockMultipartFile file = new MockMultipartFile(
                        "resumePdf",
                        "resume-" + idx + ".pdf",
                        "application/pdf",
                        ("resume-" + idx).getBytes(StandardCharsets.UTF_8)
                );
                long begin = System.nanoTime();
                InterviewQuestionRespDTO response = facade.extractInterviewQuestions("session-pressure-2", file, 2002L, "tester");
                return new ExtractionTaskResult(response, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
            }));
        }

        start.countDown();

        int successCount = 0;
        int failCount = 0;
        List<Long> latencies = new ArrayList<>();
        for (Future<ExtractionTaskResult> future : futures) {
            ExtractionTaskResult taskResult = future.get(5, TimeUnit.SECONDS);
            latencies.add(taskResult.latencyMs());
            InterviewQuestionRespDTO resp = taskResult.response();
            if (Objects.equals(resp.getIsSuccess(), 1)) {
                successCount++;
            } else {
                failCount++;
            }
        }
        pool.shutdownNow();
        PressureTestReportUtil.printSummary(
                "facade.extraction.concurrent-ready-draft-transition",
                concurrency,
                successCount,
                failCount,
                latencies,
                Map.of("sessionId", "session-pressure-2")
        );

        assertEquals(concurrency, successCount + failCount);
        assertTrue(successCount > 0);
        assertTrue(failCount > 0);
        verify(sessionService, times(concurrency)).markResumeUploading("session-pressure-2", 2002L);
        verify(sessionService, times(successCount)).markReady(anyString(), anyLong(), anyString(), anyString());
        verify(sessionService, times(failCount)).markDraft("session-pressure-2", 2002L);
    }

    private record AnswerTaskResult(InterviewAnswerRespDTO response, long latencyMs) {
    }

    private record ExtractionTaskResult(InterviewQuestionRespDTO response, long latencyMs) {
    }
}
