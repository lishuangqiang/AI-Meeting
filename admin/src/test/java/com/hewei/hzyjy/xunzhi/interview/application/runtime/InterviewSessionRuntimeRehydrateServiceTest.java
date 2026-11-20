package com.hewei.hzyjy.xunzhi.interview.application.runtime;

import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionTurnArchive;
import com.hewei.hzyjy.xunzhi.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.xunzhi.interview.dao.repository.InterviewSessionTurnArchiveRepository;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeConfidence;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeLoadMode;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewSessionRuntimeRehydrateServiceTest {

    @Mock
    private InterviewSessionRuntimeSnapshotService runtimeSnapshotService;

    @Mock
    private InterviewSessionRuntimeLockService runtimeLockService;

    @Mock
    private InterviewSessionService interviewSessionService;

    @Mock
    private InterviewQuestionService interviewQuestionService;

    @Mock
    private InterviewQuestionCacheService interviewQuestionCacheService;

    @Mock
    private InterviewRecordMapper interviewRecordMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private InterviewSessionRuntimeRehydrateService service;

    @BeforeEach
    void setUp() {
        service = new InterviewSessionRuntimeRehydrateService(
                runtimeSnapshotService,
                runtimeLockService,
                interviewSessionService,
                interviewQuestionService,
                interviewQuestionCacheService,
                interviewRecordMapper,
                stringRedisTemplate
        );
    }

    @Test
    void restoresProgressFromAnAnswerArchivedAfterTheSnapshot() throws InterruptedException {
        String sessionId = "interview";
        var archives = mock(InterviewSessionTurnArchiveRepository.class);
        var snapshots = spy(new InterviewSessionRuntimeSnapshotService(null, null, archives,
                interviewSessionService, interviewQuestionService, interviewQuestionCacheService));
        var snapshot = new InterviewSessionRuntimeSnapshot();
        snapshot.setSessionId(sessionId);
        snapshot.setArchiveWatermark(0L);
        snapshot.setQuestions(Map.of("1", "First?", "2", "Second?"));
        var flow = new InterviewFlowState();
        flow.setStatus("ASKING");
        flow.setCurrentQuestionNumber("1");
        flow.setCurrentIndex(0);
        flow.setTotalQuestions(2);
        flow.setMaxFollowUp(3);
        snapshot.setFlow(flow);
        var archived = new InterviewSessionTurnArchive();
        archived.setSeq(1L);
        archived.setTurnPayload(InterviewTurnLog.builder().questionNumber("1").nextQuestionNumber("2")
                .score(85).totalScore(85).build());
        doReturn(Optional.of(snapshot)).when(snapshots).findSnapshot(sessionId);
        doReturn(Optional.empty()).when(snapshots).findHotSnapshot(sessionId);
        doReturn(Optional.empty()).when(snapshots).findColdSnapshot(sessionId);
        when(archives.findBySessionIdOrderBySeqAsc(sessionId)).thenReturn(List.of(archived));
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(runtimeLockService.acquire(sessionId)).thenReturn(mock(RLock.class));

        var recovery = new InterviewSessionRuntimeRehydrateService(snapshots, runtimeLockService,
                interviewSessionService, interviewQuestionService, interviewQuestionCacheService,
                interviewRecordMapper, stringRedisTemplate);
        var view = recovery.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                InterviewRuntimeRehydrateScope.FLOW_ONLY);

        var recovered = view.getSnapshot();
        assertEquals("2", recovered.getFlow().getCurrentQuestionNumber());
        assertEquals(1, recovered.getFlow().getCurrentIndex());
        assertEquals(3, recovered.getFlow().getMaxFollowUp());
        assertEquals(1, recovered.getScoreAggregate().getScoreCount());
        assertEquals(85, recovered.getScoreAggregate().getTotalScore());
        assertEquals(1L, recovered.getArchiveWatermark());
        assertEquals(List.of(archived.getTurnPayload()), recovered.getRecentTurns());

        // Re-reading the same archive cannot advance progress or count the answer twice.
        snapshots.loadPersistedTurns(sessionId, recovered);
        assertEquals(1, recovered.getScoreAggregate().getScoreCount());
        verify(interviewQuestionCacheService).restoreInterviewFlow(sessionId, recovered.getFlow());
    }

    @Test
    void restoresFollowUpAndCompletionWithoutScoringTheFollowUpTwice() {
        var archives = mock(InterviewSessionTurnArchiveRepository.class);
        var snapshots = new InterviewSessionRuntimeSnapshotService(null, null, archives,
                interviewSessionService, interviewQuestionService, interviewQuestionCacheService);
        var snapshot = new InterviewSessionRuntimeSnapshot();
        snapshot.setArchiveWatermark(0L);
        snapshot.setQuestions(Map.of("1", "First?"));
        var answer = new InterviewSessionTurnArchive();
        answer.setSeq(1L);
        answer.setTurnPayload(InterviewTurnLog.builder().requestId("answer").questionNumber("1")
                .score(70).totalScore(70).nextQuestionNumber("1-F1").nextQuestion("Why?").build());
        var followUp = new InterviewSessionTurnArchive();
        followUp.setSeq(2L);
        followUp.setTurnPayload(InterviewTurnLog.builder().requestId("followup").questionNumber("1-F1")
                .isFollowUp(true).score(90).totalScore(70).finished(true).build());
        when(archives.findBySessionIdOrderBySeqAsc("interview"))
                .thenReturn(List.of(answer), List.of(answer, followUp));

        snapshots.loadPersistedTurns("interview", snapshot);
        assertEquals("1-F1", snapshot.getFlow().getCurrentQuestionNumber());
        assertEquals("Why?", snapshot.getFollowUpQuestions().get("1-F1"));

        snapshots.loadPersistedTurns("interview", snapshot);
        assertEquals("COMPLETED", snapshot.getFlow().getStatus());
        assertEquals(1, snapshot.getScoreAggregate().getScoreCount());
        assertEquals(70, snapshot.getScoreAggregate().getScoreSum());
        assertEquals(2L, snapshot.getLastTurnSeq());
    }

    @Test
    void shouldNotRebuildRuntimeWhenRehydrateLockCannotBeAcquired() throws InterruptedException {
        String sessionId = "session-1";

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.size(anyString())).thenReturn(0L);
        when(runtimeLockService.acquire(sessionId)).thenReturn(null);
        when(runtimeLockService.acquire(sessionId, 80L)).thenReturn(null);
        when(runtimeSnapshotService.findSnapshot(sessionId)).thenReturn(Optional.empty());

        InterviewSessionRuntimeView result = service.ensureRuntime(
                sessionId,
                InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                InterviewRuntimeRehydrateScope.FULL_RUNTIME
        );

        assertAll(
                () -> assertEquals(InterviewRuntimeLoadMode.READ_WRITE_REQUIRED, result.getLoadMode()),
                () -> assertEquals(InterviewRuntimeRestoreSource.NONE, result.getRestoreSource()),
                () -> assertEquals(InterviewRuntimeConfidence.READ_ONLY, result.getConfidence()),
                () -> assertFalse(result.isCacheRebuilt()),
                () -> assertFalse(result.canWrite()),
                () -> assertNull(result.getSnapshot())
        );

        verify(runtimeLockService).acquire(sessionId);
        verify(runtimeLockService).acquire(sessionId, 80L);
        verify(runtimeLockService).release(isNull());
        verify(interviewRecordMapper, never()).selectOne(any());
        verify(interviewSessionService, never()).getBySessionId(anyString());
        verify(interviewQuestionService, never()).getBySessionId(anyString());
    }
}
