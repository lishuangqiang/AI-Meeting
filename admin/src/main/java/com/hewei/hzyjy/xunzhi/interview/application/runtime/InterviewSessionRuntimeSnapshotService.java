package com.hewei.hzyjy.xunzhi.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionRuntimeColdSnapshot;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionRuntimeHotSnapshot;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionTurnArchive;
import com.hewei.hzyjy.xunzhi.interview.dao.repository.InterviewSessionRuntimeColdSnapshotRepository;
import com.hewei.hzyjy.xunzhi.interview.dao.repository.InterviewSessionRuntimeHotSnapshotRepository;
import com.hewei.hzyjy.xunzhi.interview.dao.repository.InterviewSessionTurnArchiveRepository;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeConfidence;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeScoreAggregate;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 提供面试会话运行态快照与轮次归档的持久化能力，
 * 用于在关键业务节点保存恢复材料并支持软回放幂等与会话重建。
 *
 * @author 程序员牛肉
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionRuntimeSnapshotService {

    private static final int RECENT_TURN_LIMIT = 20;
    private static final int HOT_SNAPSHOT_CAS_MAX_RETRIES = 3;
    private static final long HOT_SNAPSHOT_CAS_BASE_BACKOFF_MILLIS = 20L;

    private final InterviewSessionRuntimeHotSnapshotRepository hotSnapshotRepository;
    private final InterviewSessionRuntimeColdSnapshotRepository coldSnapshotRepository;
    private final InterviewSessionTurnArchiveRepository turnArchiveRepository;
    private final InterviewSessionService interviewSessionService;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewQuestionCacheService interviewQuestionCacheService;

    @Lazy
    @Autowired
    private InterviewSessionRuntimeHotRefreshCoordinator hotRefreshCoordinator;

    public void initializeDraftSnapshot(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            return;
        }
        try {
            InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(session.getSessionId()).orElse(null);
            InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(session.getSessionId()).orElse(null);
            Date now = new Date();
            hotSnapshotRepository.applyPatch(session.getSessionId(), InterviewSessionRuntimeHotPatch.builder()
                    .userId(session.getUserId())
                    .sessionStatus(session.getStatus())
                    .snapshotVersion(nextVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion()))
                    .snapshotLevel("DRAFT")
                    .rebuildConfidence(InterviewRuntimeConfidence.READ_ONLY)
                    .snapshotUpdatedAt(now)
                    .scoreAggregate(hotSnapshot != null && hotSnapshot.getScoreAggregate() != null ? hotSnapshot.getScoreAggregate() : emptyScoreAggregate())
                    .recentTurns(hotSnapshot != null && hotSnapshot.getRecentTurns() != null ? hotSnapshot.getRecentTurns() : new ArrayList<>())
                    .recentTurnCount(hotSnapshot != null && hotSnapshot.getRecentTurnCount() != null ? hotSnapshot.getRecentTurnCount() : 0)
                    .lastTurnSeq(hotSnapshot != null && hotSnapshot.getLastTurnSeq() != null ? hotSnapshot.getLastTurnSeq() : 0L)
                    .build());
            coldSnapshotRepository.applyPatch(session.getSessionId(), InterviewSessionRuntimeColdPatch.builder()
                    .userId(session.getUserId())
                    .materialVersion(nextVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion()))
                    .resumeFileUrl(session.getResumeFileUrl())
                    .interviewType(session.getInterviewType())
                    .materialUpdatedAt(now)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to initialize runtime snapshot, sessionId={}", session.getSessionId(), ex);
        }
    }

    public Optional<InterviewSessionRuntimeSnapshot> findSnapshot(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(sessionId).orElse(null);
        InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(sessionId).orElse(null);
        return Optional.ofNullable(assembleSnapshot(hotSnapshot, coldSnapshot));
    }

    public Optional<InterviewSessionRuntimeHotSnapshot> findHotSnapshot(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        return hotSnapshotRepository.findBySessionId(sessionId);
    }

    public Optional<InterviewSessionRuntimeColdSnapshot> findColdSnapshot(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        return coldSnapshotRepository.findBySessionId(sessionId);
    }

    public void refreshAfterQuestionExtraction(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.questionReady(sessionId));
    }

    public void refreshAfterDemeanorEvaluated(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.demeanorReady(sessionId));
    }

    public void refreshAfterAnswerCommitted(String sessionId, String requestId, InterviewTurnLog turnLog) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.answerCommitted(sessionId, requestId, turnLog));
    }

    public void refreshAfterFinalize(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.finalized(sessionId));
    }

    public boolean flushHotRefreshRequest(InterviewSessionRuntimeHotRefreshRequest request) {
        if (request == null) {
            return false;
        }
        return refreshSnapshot(
                request.getSessionId(),
                request.getSnapshotLevel(),
                request.getRequestId(),
                request.getCommittedTurn(),
                request.isPersistTurnArchive()
        );
    }

    public List<InterviewTurnLog> loadPersistedTurns(String sessionId) {
        return loadPersistedTurns(sessionId, null);
    }

    List<InterviewTurnLog> loadPersistedTurns(String sessionId, InterviewSessionRuntimeSnapshot recoverySnapshot) {
        if (StrUtil.isBlank(sessionId)) {
            return new ArrayList<>();
        }
        List<InterviewSessionTurnArchive> archives = loadTurnArchives(sessionId);
        if (archives != null && !archives.isEmpty()) {
            if (recoverySnapshot != null) {
                applyArchivedProgress(recoverySnapshot, archives);
            }
            return archives.stream()
                    .map(InterviewSessionTurnArchive::getTurnPayload)
                    .filter(item -> item != null)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        List<InterviewTurnLog> cacheTurns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (cacheTurns != null && !cacheTurns.isEmpty()) {
            return cacheTurns;
        }
        return findSnapshot(sessionId)
                .map(InterviewSessionRuntimeSnapshot::getRecentTurns)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }

    private void applyArchivedProgress(InterviewSessionRuntimeSnapshot snapshot, List<InterviewSessionTurnArchive> archives) {
        Long watermark = snapshot.getArchiveWatermark() == null ? snapshot.getLastTurnSeq() : snapshot.getArchiveWatermark();
        if (watermark == null) {
            return;
        }
        InterviewFlowState flow = snapshot.getFlow();
        int totalQuestions = snapshot.getQuestions() == null ? 0 : snapshot.getQuestions().size();
        if (flow != null && flow.getTotalQuestions() != null) {
            totalQuestions = flow.getTotalQuestions();
        }
        InterviewRuntimeScoreAggregate scores = snapshot.getScoreAggregate();
        if (scores == null) {
            scores = emptyScoreAggregate();
        }
        for (InterviewSessionTurnArchive archive : archives) {
            InterviewTurnLog turn = archive.getTurnPayload();
            if (archive.getSeq() == null || archive.getSeq() <= watermark || turn == null) {
                continue;
            }
            if ((flow == null || !flow.isCompleted()) && snapshot.getRebuildConfidence() != InterviewRuntimeConfidence.TERMINAL) {
                InterviewFlowState next = deriveFlowFromTurns(List.of(turn), totalQuestions);
                if (flow != null && next != null) {
                    next.setMaxFollowUp(flow.getMaxFollowUp());
                    next.setVersion(flow.getVersion() == null ? 1 : flow.getVersion() + 1);
                }
                flow = next;
                snapshot.setFlow(flow);
            }
            if (turn.getScore() != null && !Boolean.TRUE.equals(turn.getIsFollowUp())) {
                scores.setScoreSum(Objects.requireNonNullElse(scores.getScoreSum(), 0) + clampScore(turn.getScore()));
                scores.setScoreCount(Objects.requireNonNullElse(scores.getScoreCount(), 0) + 1);
            }
            if (turn.getTotalScore() != null) {
                scores.setTotalScore(turn.getTotalScore());
            }
            snapshot.setScoreAggregate(scores);
            String nextQuestion = normalizeQuestionNumber(turn.getNextQuestionNumber());
            if (isFollowUpQuestion(nextQuestion) && StrUtil.isNotBlank(turn.getNextQuestion())) {
                if (snapshot.getFollowUpQuestions() == null) {
                    snapshot.setFollowUpQuestions(new LinkedHashMap<>());
                }
                snapshot.getFollowUpQuestions().put(nextQuestion, turn.getNextQuestion().trim());
            }
            List<InterviewTurnLog> recent = snapshot.getRecentTurns() == null
                    ? new ArrayList<>() : new ArrayList<>(snapshot.getRecentTurns());
            appendCommittedTurnIfAbsent(recent, turn);
            snapshot.setRecentTurns(limitRecentTurns(recent));
            snapshot.setRecentTurnCount(snapshot.getRecentTurns().size());
            snapshot.setLastCommittedQuestionNumber(normalizeQuestionNumber(turn.getQuestionNumber()));
            snapshot.setLastCommittedTurnDigest(buildTurnDigest(turn.getQuestionNumber(), turn.getAnswerContent()));
            snapshot.setLastAppliedRequestId(turn.getRequestId());
            watermark = archive.getSeq();
            snapshot.setArchiveWatermark(watermark);
            snapshot.setLastTurnSeq(watermark);
        }
    }

    public InterviewAnswerRespDTO findReplayResponse(
            String sessionId,
            String requestId,
            String questionNumber,
            String answerContent) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        InterviewSessionRuntimeSnapshot snapshot = findSnapshot(sessionId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        InterviewTurnLog matchedTurn = findReplayTurn(snapshot, requestId, questionNumber, answerContent);
        return matchedTurn == null ? null : buildReplayResponse(matchedTurn);
    }

    public List<InterviewSessionTurnArchive> loadTurnArchives(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        return turnArchiveRepository.findBySessionIdOrderBySeqAsc(sessionId);
    }

    public Optional<InterviewSessionTurnArchive> findTurnArchive(String sessionId, String requestId) {
        if (sessionId == null || sessionId.isBlank() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return turnArchiveRepository.findBySessionIdAndRequestId(sessionId, requestId);
    }

    private boolean refreshSnapshot(
            String sessionId,
            String snapshotLevel,
            String requestId,
            InterviewTurnLog committedTurn,
            boolean persistTurnArchive) {
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }
        try {
            InterviewSession session = interviewSessionService.getBySessionId(sessionId);
            if (session == null) {
                return false;
            }
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            for (int attempt = 0; attempt < HOT_SNAPSHOT_CAS_MAX_RETRIES; attempt++) {
                InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(sessionId).orElse(null);
                InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(sessionId).orElse(null);
                InterviewSessionRuntimeSnapshot snapshot = assembleSnapshot(hotSnapshot, coldSnapshot);

                Map<String, String> questions = resolveQuestions(sessionId, question);
                Map<String, String> suggestions = resolveSuggestions(sessionId, question);
                Map<String, Object> resumeContext = resolveResumeContext(sessionId, question);
                Long archiveWatermark = persistTurnArchive
                        ? archiveTurn(sessionId, requestId, committedTurn, hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion())
                        : resolveArchiveWatermark(hotSnapshot == null ? null : hotSnapshot.getArchiveWatermark(), sessionId);
                List<InterviewTurnLog> turns = resolveTurns(sessionId, snapshot, committedTurn);
                InterviewFlowState flow = resolveFlow(sessionId, session, questions, turns);
                InterviewRuntimeScoreAggregate scoreAggregate = resolveScoreAggregate(sessionId, turns);
                List<InterviewTurnLog> recentTurns = limitRecentTurns(turns);
                Date now = new Date();
                String mutationId = normalizeMutationId(requestId);
                InterviewSessionRuntimeHotPatch hotPatch = buildHotPatch(
                        session,
                        snapshotLevel,
                        mutationId,
                        committedTurn,
                        questions,
                        turns,
                        flow,
                        scoreAggregate,
                        recentTurns,
                        archiveWatermark,
                        hotSnapshot,
                        now
                );

                if (shouldSkipHotPatch(hotSnapshot, hotPatch)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                if (hotSnapshot == null || hotSnapshot.getSnapshotVersion() == null) {
                    seedHotSnapshot(sessionId, session, snapshotLevel, now);
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    continue;
                }

                if (isMutationAlreadyApplied(hotSnapshot, mutationId)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                validateHotPatchMonotonicity(hotSnapshot, hotPatch);

                boolean updated = hotSnapshotRepository.compareAndSetPatch(sessionId, hotSnapshot.getSnapshotVersion(), hotPatch);
                if (updated) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                InterviewSessionRuntimeHotSnapshot latestSnapshot = findHotSnapshot(sessionId).orElse(null);
                if (isMutationAlreadyApplied(latestSnapshot, mutationId)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                backoffHotSnapshotRetry(attempt);
            }
            log.warn("Hot snapshot CAS retries exhausted, sessionId={}, snapshotLevel={}, requestId={}",
                    sessionId, snapshotLevel, requestId);
            return false;
        } catch (Exception ex) {
            log.warn("Failed to refresh runtime snapshot, sessionId={}", sessionId, ex);
            return false;
        }
    }

    private InterviewSessionRuntimeHotPatch buildHotPatch(
            InterviewSession session,
            String snapshotLevel,
            String mutationId,
            InterviewTurnLog committedTurn,
            Map<String, String> questions,
            List<InterviewTurnLog> turns,
            InterviewFlowState flow,
            InterviewRuntimeScoreAggregate scoreAggregate,
            List<InterviewTurnLog> recentTurns,
            Long archiveWatermark,
            InterviewSessionRuntimeHotSnapshot hotSnapshot,
            Date now) {
        return InterviewSessionRuntimeHotPatch.builder()
                .userId(session.getUserId())
                .sessionStatus(session.getStatus())
                .snapshotVersion(nextVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion()))
                .snapshotLevel(snapshotLevel)
                .rebuildConfidence(resolveConfidence(session, questions, flow))
                .snapshotUpdatedAt(now)
                .flow(flow)
                .scoreAggregate(scoreAggregate)
                .followUpQuestions(resolveFollowUpQuestions(session.getSessionId(), turns))
                .recentTurns(recentTurns)
                .recentTurnCount(recentTurns.size())
                .archiveWatermark(archiveWatermark)
                .lastTurnSeq(archiveWatermark)
                .lastAppliedRequestId(mutationId)
                .lastMutationId(mutationId)
                .lastMutationTime(StrUtil.isBlank(mutationId) ? null : now)
                .lastCommittedQuestionNumber(committedTurn == null ? null : normalizeQuestionNumber(committedTurn.getQuestionNumber()))
                .lastCommittedTurnDigest(committedTurn == null ? null : buildTurnDigest(committedTurn.getQuestionNumber(), committedTurn.getAnswerContent()))
                .build();
    }

    private void applyColdSnapshotIfNecessary(
            String sessionId,
            String snapshotLevel,
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeColdSnapshot coldSnapshot,
            Map<String, String> questions,
            Map<String, String> suggestions,
            Map<String, Object> resumeContext,
            Date now) {
        if (coldSnapshot != null && StrUtil.equals("ACTIVE", snapshotLevel)) {
            return;
        }
        coldSnapshotRepository.applyPatch(sessionId, InterviewSessionRuntimeColdPatch.builder()
                .userId(session.getUserId())
                .materialVersion(nextVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion()))
                .resumeFileUrl(session.getResumeFileUrl())
                .interviewType(resolveInterviewType(session, question, coldSnapshot))
                .direction(resolveDirection(sessionId, session, question))
                .questions(questions)
                .suggestions(suggestions)
                .resumeContext(resumeContext)
                .resumeScore(resolveResumeScore(sessionId, question))
                .demeanorScore(interviewQuestionCacheService.getSessionDemeanorScore(sessionId))
                .demeanorDetails(interviewQuestionCacheService.getSessionDemeanorScoreDetails(sessionId))
                .materialUpdatedAt(now)
                .build());
    }

    private void seedHotSnapshot(String sessionId, InterviewSession session, String snapshotLevel, Date now) {
        hotSnapshotRepository.applyPatch(sessionId, InterviewSessionRuntimeHotPatch.builder()
                .userId(session.getUserId())
                .sessionStatus(session.getStatus())
                .snapshotVersion(1L)
                .snapshotLevel(snapshotLevel)
                .rebuildConfidence(InterviewRuntimeConfidence.READ_ONLY)
                .snapshotUpdatedAt(now)
                .scoreAggregate(emptyScoreAggregate())
                .recentTurns(new ArrayList<>())
                .recentTurnCount(0)
                .archiveWatermark(resolveArchiveWatermark(null, sessionId))
                .lastTurnSeq(0L)
                .build());
    }

    private boolean isMutationAlreadyApplied(InterviewSessionRuntimeHotSnapshot hotSnapshot, String mutationId) {
        if (hotSnapshot == null || StrUtil.isBlank(mutationId)) {
            return false;
        }
        return StrUtil.equals(StrUtil.trim(hotSnapshot.getLastMutationId()), mutationId);
    }

    private boolean shouldSkipHotPatch(InterviewSessionRuntimeHotSnapshot hotSnapshot, InterviewSessionRuntimeHotPatch hotPatch) {
        if (hotSnapshot == null || hotPatch == null) {
            return false;
        }
        return Objects.equals(hotSnapshot.getUserId(), hotPatch.getUserId())
                && StrUtil.equals(hotSnapshot.getSessionStatus(), hotPatch.getSessionStatus())
                && StrUtil.equals(hotSnapshot.getSnapshotLevel(), hotPatch.getSnapshotLevel())
                && hotSnapshot.getRebuildConfidence() == hotPatch.getRebuildConfidence()
                && Objects.equals(hotSnapshot.getFlow(), hotPatch.getFlow())
                && Objects.equals(hotSnapshot.getScoreAggregate(), hotPatch.getScoreAggregate())
                && Objects.equals(hotSnapshot.getFollowUpQuestions(), hotPatch.getFollowUpQuestions())
                && Objects.equals(hotSnapshot.getRecentTurns(), hotPatch.getRecentTurns())
                && Objects.equals(hotSnapshot.getRecentTurnCount(), hotPatch.getRecentTurnCount())
                && Objects.equals(hotSnapshot.getArchiveWatermark(), hotPatch.getArchiveWatermark())
                && Objects.equals(hotSnapshot.getLastTurnSeq(), hotPatch.getLastTurnSeq())
                && StrUtil.equals(hotSnapshot.getLastAppliedRequestId(), hotPatch.getLastAppliedRequestId())
                && StrUtil.equals(hotSnapshot.getLastMutationId(), hotPatch.getLastMutationId())
                && StrUtil.equals(hotSnapshot.getLastCommittedQuestionNumber(), hotPatch.getLastCommittedQuestionNumber())
                && StrUtil.equals(hotSnapshot.getLastCommittedTurnDigest(), hotPatch.getLastCommittedTurnDigest());
    }

    private void validateHotPatchMonotonicity(
            InterviewSessionRuntimeHotSnapshot current,
            InterviewSessionRuntimeHotPatch patch) {
        if (current == null || patch == null) {
            return;
        }
        if (current.getLastTurnSeq() != null && patch.getLastTurnSeq() != null
                && patch.getLastTurnSeq() < current.getLastTurnSeq()) {
            throw new IllegalStateException("hot snapshot lastTurnSeq regressed");
        }
        if (current.getArchiveWatermark() != null && patch.getArchiveWatermark() != null
                && patch.getArchiveWatermark() < current.getArchiveWatermark()) {
            throw new IllegalStateException("hot snapshot archiveWatermark regressed");
        }
        if (current.getScoreAggregate() != null && patch.getScoreAggregate() != null) {
            Integer currentScoreCount = current.getScoreAggregate().getScoreCount();
            Integer patchScoreCount = patch.getScoreAggregate().getScoreCount();
            if (currentScoreCount != null && patchScoreCount != null && patchScoreCount < currentScoreCount) {
                throw new IllegalStateException("hot snapshot scoreCount regressed");
            }
        }
        if (current.getFlow() != null && patch.getFlow() != null) {
            Integer currentIndex = current.getFlow().getCurrentIndex();
            Integer patchIndex = patch.getFlow().getCurrentIndex();
            if (!current.getFlow().isCompleted() && currentIndex != null && patchIndex != null && patchIndex < currentIndex) {
                throw new IllegalStateException("hot snapshot flow index regressed");
            }
        }
    }

    private String normalizeMutationId(String requestId) {
        return StrUtil.isBlank(requestId) ? null : requestId.trim();
    }

    private void backoffHotSnapshotRetry(int attempt) {
        long baseDelay = HOT_SNAPSHOT_CAS_BASE_BACKOFF_MILLIS * (attempt + 1L);
        long jitter = ThreadLocalRandom.current().nextLong(10L, 30L);
        try {
            TimeUnit.MILLISECONDS.sleep(baseDelay + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Long archiveTurn(String sessionId, String requestId, InterviewTurnLog turn, Long snapshotVersion) {
        if (StrUtil.isBlank(sessionId) || turn == null) {
            return resolveArchiveWatermark(null, sessionId);
        }
        if (StrUtil.isNotBlank(requestId)) {
            Optional<InterviewSessionTurnArchive> existing = turnArchiveRepository.findBySessionIdAndRequestId(sessionId, requestId.trim());
            if (existing.isPresent()) {
                return existing.get().getSeq();
            }
        }
        long nextSeq = turnArchiveRepository.findFirstBySessionIdOrderBySeqDesc(sessionId)
                .map(InterviewSessionTurnArchive::getSeq)
                .map(value -> value == null ? 1L : value + 1L)
                .orElse(1L);
        InterviewSessionTurnArchive archive = new InterviewSessionTurnArchive();
        archive.setSessionId(sessionId);
        archive.setRequestId(StrUtil.blankToDefault(requestId, null));
        archive.setSeq(nextSeq);
        archive.setSnapshotVersion(snapshotVersion == null ? 0L : snapshotVersion + 1L);
        archive.setTurnPayload(turn);
        turnArchiveRepository.save(archive);
        return nextSeq;
    }

    private InterviewTurnLog findReplayTurn(
            InterviewSessionRuntimeSnapshot snapshot,
            String requestId,
            String questionNumber,
            String answerContent) {
        List<InterviewTurnLog> recentTurns = snapshot.getRecentTurns();
        if (recentTurns != null && !recentTurns.isEmpty() && StrUtil.isNotBlank(requestId)) {
            for (int index = recentTurns.size() - 1; index >= 0; index--) {
                InterviewTurnLog turn = recentTurns.get(index);
                if (turn != null && StrUtil.equals(requestId.trim(), StrUtil.blankToDefault(turn.getRequestId(), "").trim())) {
                    return turn;
                }
            }
        }
        if (StrUtil.isBlank(questionNumber) || StrUtil.isBlank(answerContent)) {
            return null;
        }
        String expectedDigest = buildTurnDigest(questionNumber, answerContent);
        if (recentTurns != null && !recentTurns.isEmpty()) {
            for (int index = recentTurns.size() - 1; index >= 0; index--) {
                InterviewTurnLog turn = recentTurns.get(index);
                if (turn != null && StrUtil.equals(expectedDigest, buildTurnDigest(turn.getQuestionNumber(), turn.getAnswerContent()))) {
                    return turn;
                }
            }
        }
        if (StrUtil.equals(snapshot.getLastCommittedTurnDigest(), expectedDigest)
                && StrUtil.equals(normalizeQuestionNumber(snapshot.getLastCommittedQuestionNumber()), normalizeQuestionNumber(questionNumber))) {
            List<InterviewSessionTurnArchive> archives = loadTurnArchives(snapshot.getSessionId());
            if (archives != null && !archives.isEmpty()) {
                InterviewSessionTurnArchive lastArchive = archives.get(archives.size() - 1);
                return lastArchive == null ? null : lastArchive.getTurnPayload();
            }
        }
        return null;
    }

    private InterviewAnswerRespDTO buildReplayResponse(InterviewTurnLog turn) {
        if (turn == null) {
            return null;
        }
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        response.withCurrentQuestion(turn.getQuestionNumber(), turn.getQuestionContent());
        response.withEvaluation(turn.getScore(), turn.getFeedback(), turn.getTotalScore());
        if (Boolean.TRUE.equals(turn.getFinished())) {
            response.finish().success();
            return response;
        }
        String nextQuestionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
        response.withNextQuestion(
                nextQuestionNumber,
                turn.getNextQuestion(),
                isFollowUpQuestion(nextQuestionNumber),
                resolveFollowUpCount(nextQuestionNumber)
        ).success();
        return response;
    }

    private InterviewSessionRuntimeSnapshot assembleSnapshot(
            InterviewSessionRuntimeHotSnapshot hotSnapshot,
            InterviewSessionRuntimeColdSnapshot coldSnapshot) {
        if (hotSnapshot == null && coldSnapshot == null) {
            return null;
        }
        InterviewSessionRuntimeSnapshot snapshot = new InterviewSessionRuntimeSnapshot();
        snapshot.setId(hotSnapshot != null ? hotSnapshot.getId() : coldSnapshot.getId());
        snapshot.setSessionId(hotSnapshot != null ? hotSnapshot.getSessionId() : coldSnapshot.getSessionId());
        snapshot.setUserId(hotSnapshot != null ? hotSnapshot.getUserId() : coldSnapshot.getUserId());
        snapshot.setSessionStatus(hotSnapshot == null ? null : hotSnapshot.getSessionStatus());
        snapshot.setSnapshotVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion());
        snapshot.setSnapshotLevel(hotSnapshot == null ? null : hotSnapshot.getSnapshotLevel());
        snapshot.setRebuildConfidence(hotSnapshot == null ? null : hotSnapshot.getRebuildConfidence());
        snapshot.setSnapshotUpdatedAt(hotSnapshot == null ? null : hotSnapshot.getSnapshotUpdatedAt());
        snapshot.setResumeFileUrl(coldSnapshot == null ? null : coldSnapshot.getResumeFileUrl());
        snapshot.setInterviewType(coldSnapshot == null ? null : coldSnapshot.getInterviewType());
        snapshot.setDirection(coldSnapshot == null ? null : coldSnapshot.getDirection());
        snapshot.setQuestions(coldSnapshot == null || coldSnapshot.getQuestions() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getQuestions()));
        snapshot.setSuggestions(coldSnapshot == null || coldSnapshot.getSuggestions() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getSuggestions()));
        snapshot.setResumeContext(coldSnapshot == null || coldSnapshot.getResumeContext() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getResumeContext()));
        snapshot.setResumeScore(coldSnapshot == null ? null : coldSnapshot.getResumeScore());
        snapshot.setDemeanorScore(coldSnapshot == null ? null : coldSnapshot.getDemeanorScore());
        snapshot.setDemeanorDetails(coldSnapshot == null ? null : coldSnapshot.getDemeanorDetails());
        snapshot.setFlow(hotSnapshot == null ? null : hotSnapshot.getFlow());
        snapshot.setScoreAggregate(hotSnapshot == null ? null : hotSnapshot.getScoreAggregate());
        snapshot.setFollowUpQuestions(hotSnapshot == null || hotSnapshot.getFollowUpQuestions() == null
                ? null : new LinkedHashMap<>(hotSnapshot.getFollowUpQuestions()));
        snapshot.setRecentTurns(hotSnapshot == null || hotSnapshot.getRecentTurns() == null
                ? null : new ArrayList<>(hotSnapshot.getRecentTurns()));
        snapshot.setRecentTurnCount(hotSnapshot == null ? null : hotSnapshot.getRecentTurnCount());
        snapshot.setArchiveWatermark(hotSnapshot == null ? null : hotSnapshot.getArchiveWatermark());
        snapshot.setLastTurnSeq(hotSnapshot == null ? null : hotSnapshot.getLastTurnSeq());
        snapshot.setLastAppliedRequestId(hotSnapshot == null ? null : hotSnapshot.getLastAppliedRequestId());
        snapshot.setLastMutationId(hotSnapshot == null ? null : hotSnapshot.getLastMutationId());
        snapshot.setLastMutationTime(hotSnapshot == null ? null : hotSnapshot.getLastMutationTime());
        snapshot.setLastCommittedQuestionNumber(hotSnapshot == null ? null : hotSnapshot.getLastCommittedQuestionNumber());
        snapshot.setLastCommittedTurnDigest(hotSnapshot == null ? null : hotSnapshot.getLastCommittedTurnDigest());
        snapshot.setMaterialVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion());
        snapshot.setCreateTime(hotSnapshot != null ? hotSnapshot.getCreateTime() : coldSnapshot.getCreateTime());
        snapshot.setUpdateTime(hotSnapshot != null ? hotSnapshot.getUpdateTime() : coldSnapshot.getUpdateTime());
        return snapshot;
    }

    private String resolveInterviewType(
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeColdSnapshot coldSnapshot) {
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        if (coldSnapshot != null && StrUtil.isNotBlank(coldSnapshot.getInterviewType())) {
            return coldSnapshot.getInterviewType().trim();
        }
        return session == null ? null : session.getInterviewType();
    }

    private Map<String, String> resolveQuestions(String sessionId, InterviewQuestion question) {
        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions != null && !questions.isEmpty()) {
            return new LinkedHashMap<>(questions);
        }
        return parseStringMap(question == null ? null : question.getQuestionsJson(), question == null ? null : question.getQuestions());
    }

    private Map<String, String> resolveSuggestions(String sessionId, InterviewQuestion question) {
        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions != null && !suggestions.isEmpty()) {
            return new LinkedHashMap<>(suggestions);
        }
        return parseStringMap(question == null ? null : question.getSuggestionsJson(), question == null ? null : question.getSuggestions());
    }

    private Map<String, Object> resolveResumeContext(String sessionId, InterviewQuestion question) {
        Map<String, Object> resumeContext = interviewQuestionCacheService.getSessionResumeContext(sessionId);
        if (resumeContext != null && !resumeContext.isEmpty()) {
            return new LinkedHashMap<>(resumeContext);
        }
        if (question == null || StrUtil.isBlank(question.getRawResponseData())) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(question.getRawResponseData(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private Integer resolveResumeScore(String sessionId, InterviewQuestion question) {
        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore != null) {
            return resumeScore;
        }
        return question == null ? null : question.getResumeScore();
    }

    private String resolveDirection(String sessionId, InterviewSession session, InterviewQuestion question) {
        String direction = interviewQuestionCacheService.getSessionInterviewDirection(sessionId);
        if (StrUtil.isNotBlank(direction)) {
            return direction;
        }
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        return session == null ? null : session.getInterviewType();
    }

    private List<InterviewTurnLog> resolveTurns(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewTurnLog committedTurn) {
        List<InterviewTurnLog> turns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (turns != null && !turns.isEmpty()) {
            List<InterviewTurnLog> merged = new ArrayList<>(turns);
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        List<InterviewSessionTurnArchive> archives = loadTurnArchives(sessionId);
        if (archives != null && !archives.isEmpty()) {
            List<InterviewTurnLog> merged = archives.stream()
                    .map(InterviewSessionTurnArchive::getTurnPayload)
                    .filter(item -> item != null)
                    .collect(Collectors.toCollection(ArrayList::new));
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        if (snapshot != null && snapshot.getRecentTurns() != null && !snapshot.getRecentTurns().isEmpty()) {
            List<InterviewTurnLog> merged = new ArrayList<>(snapshot.getRecentTurns());
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        if (committedTurn == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(committedTurn));
    }

    private void appendCommittedTurnIfAbsent(List<InterviewTurnLog> turns, InterviewTurnLog committedTurn) {
        if (turns == null || committedTurn == null) {
            return;
        }
        String requestId = committedTurn.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            turns.add(committedTurn);
            return;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn != null && StrUtil.equals(requestId.trim(), StrUtil.blankToDefault(turn.getRequestId(), "").trim())) {
                return;
            }
        }
        turns.add(committedTurn);
    }

    private List<InterviewTurnLog> limitRecentTurns(List<InterviewTurnLog> turns) {
        if (turns == null || turns.isEmpty()) {
            return new ArrayList<>();
        }
        int size = turns.size();
        int start = Math.max(0, size - RECENT_TURN_LIMIT);
        return new ArrayList<>(turns.subList(start, size));
    }

    private InterviewFlowState resolveFlow(
            String sessionId,
            InterviewSession session,
            Map<String, String> questions,
            List<InterviewTurnLog> turns) {
        InterviewFlowState flow = interviewQuestionCacheService.getInterviewFlow(sessionId);
        if (flow != null) {
            return cloneFlow(flow);
        }
        int totalQuestions = questions == null ? 0 : questions.size();
        if (isTerminalSession(session)) {
            return buildCompletedFlow(totalQuestions);
        }
        if (turns != null && !turns.isEmpty()) {
            return deriveFlowFromTurns(turns, totalQuestions);
        }
        return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
    }

    private InterviewRuntimeScoreAggregate resolveScoreAggregate(String sessionId, List<InterviewTurnLog> turns) {
        InterviewRuntimeScoreAggregate aggregate = emptyScoreAggregate();
        if (turns != null) {
            for (InterviewTurnLog turn : turns) {
                if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                    continue;
                }
                aggregate.setScoreSum((aggregate.getScoreSum() == null ? 0 : aggregate.getScoreSum()) + clampScore(turn.getScore()));
                aggregate.setScoreCount((aggregate.getScoreCount() == null ? 0 : aggregate.getScoreCount()) + 1);
            }
        }
        Integer totalScore = interviewQuestionCacheService.getSessionTotalScore(sessionId);
        aggregate.setTotalScore(totalScore == null ? 0 : totalScore);
        return aggregate;
    }

    private Map<String, String> resolveFollowUpQuestions(String sessionId, List<InterviewTurnLog> turns) {
        Map<String, String> followUpQuestions = interviewQuestionCacheService.getSessionFollowUpQuestions(sessionId);
        if (followUpQuestions != null && !followUpQuestions.isEmpty()) {
            return new LinkedHashMap<>(followUpQuestions);
        }
        Map<String, String> derived = new LinkedHashMap<>();
        if (turns == null) {
            return derived;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn == null || StrUtil.isBlank(turn.getNextQuestionNumber()) || StrUtil.isBlank(turn.getNextQuestion())) {
                continue;
            }
            String nextQuestionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
            if (isFollowUpQuestion(nextQuestionNumber)) {
                derived.put(nextQuestionNumber, turn.getNextQuestion().trim());
            }
        }
        return derived;
    }

    private Map<String, String> parseStringMap(String jsonPayload, List<String> fallbackList) {
        if (StrUtil.isNotBlank(jsonPayload)) {
            try {
                Map<String, String> parsed = JSON.parseObject(jsonPayload, new TypeReference<LinkedHashMap<String, String>>() {
                });
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        if (fallbackList == null || fallbackList.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int index = 0; index < fallbackList.size(); index++) {
            String value = fallbackList.get(index);
            if (StrUtil.isNotBlank(value)) {
                mapped.put(String.valueOf(index + 1), value);
            }
        }
        return mapped;
    }

    private InterviewRuntimeConfidence resolveConfidence(
            InterviewSession session,
            Map<String, String> questions,
            InterviewFlowState flow) {
        if (isTerminalSession(session)) {
            return InterviewRuntimeConfidence.TERMINAL;
        }
        if (questions != null && !questions.isEmpty() && flow != null) {
            return InterviewRuntimeConfidence.EXACT;
        }
        if (questions != null && !questions.isEmpty()) {
            return InterviewRuntimeConfidence.DERIVED;
        }
        return InterviewRuntimeConfidence.READ_ONLY;
    }

    private InterviewRuntimeScoreAggregate emptyScoreAggregate() {
        InterviewRuntimeScoreAggregate aggregate = new InterviewRuntimeScoreAggregate();
        aggregate.setScoreSum(0);
        aggregate.setScoreCount(0);
        aggregate.setTotalScore(0);
        return aggregate;
    }

    private Long resolveArchiveWatermark(Long existingWatermark, String sessionId) {
        if (existingWatermark != null && existingWatermark > 0) {
            return existingWatermark;
        }
        return turnArchiveRepository.findFirstBySessionIdOrderBySeqDesc(sessionId)
                .map(InterviewSessionTurnArchive::getSeq)
                .orElse(0L);
    }

    private Long nextVersion(Long currentVersion) {
        return currentVersion == null ? 1L : currentVersion + 1L;
    }

    private int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(score, 100));
    }

    private InterviewFlowState deriveFlowFromTurns(List<InterviewTurnLog> turns, int totalQuestions) {
        if (turns == null || turns.isEmpty()) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        InterviewTurnLog latestTurn = turns.get(turns.size() - 1);
        if (latestTurn == null) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        if (Boolean.TRUE.equals(latestTurn.getFinished())) {
            return buildCompletedFlow(totalQuestions);
        }
        String nextQuestionNumber = normalizeQuestionNumber(latestTurn.getNextQuestionNumber());
        if (StrUtil.isNotBlank(nextQuestionNumber)) {
            return buildFlowForQuestion(nextQuestionNumber, totalQuestions);
        }
        Integer answeredMainNo = extractMainQuestionNo(latestTurn.getQuestionNumber());
        if (answeredMainNo == null) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        if (totalQuestions > 0 && answeredMainNo >= totalQuestions) {
            return buildCompletedFlow(totalQuestions);
        }
        return buildFlowForQuestion(String.valueOf(answeredMainNo + 1), totalQuestions);
    }

    private InterviewFlowState buildInitialFlow(int totalQuestions) {
        InterviewFlowState flow = new InterviewFlowState();
        flow.setStatus("ASKING");
        flow.setCurrentIndex(0);
        flow.setCurrentQuestionNumber(totalQuestions > 0 ? "1" : null);
        flow.setTotalQuestions(totalQuestions);
        flow.setFollowUpCount(0);
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    private InterviewFlowState buildCompletedFlow(int totalQuestions) {
        InterviewFlowState flow = new InterviewFlowState();
        flow.setStatus("COMPLETED");
        flow.setCurrentIndex(Math.max(totalQuestions, 0));
        flow.setCurrentQuestionNumber(null);
        flow.setTotalQuestions(Math.max(totalQuestions, 0));
        flow.setFollowUpCount(0);
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    private InterviewFlowState buildFlowForQuestion(String questionNumber, int totalQuestions) {
        InterviewFlowState flow = new InterviewFlowState();
        Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
        flow.setStatus(isFollowUpQuestion(questionNumber) ? "FOLLOW_UP" : "ASKING");
        flow.setCurrentIndex(mainQuestionNo == null ? 0 : Math.max(mainQuestionNo - 1, 0));
        flow.setCurrentQuestionNumber(normalizeQuestionNumber(questionNumber));
        flow.setTotalQuestions(Math.max(totalQuestions, 0));
        flow.setFollowUpCount(resolveFollowUpCount(questionNumber));
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    private InterviewFlowState cloneFlow(InterviewFlowState flow) {
        if (flow == null) {
            return null;
        }
        InterviewFlowState cloned = new InterviewFlowState();
        cloned.setStatus(flow.getStatus());
        cloned.setCurrentIndex(flow.getCurrentIndex());
        cloned.setCurrentQuestionNumber(flow.getCurrentQuestionNumber());
        cloned.setTotalQuestions(flow.getTotalQuestions());
        cloned.setFollowUpCount(flow.getFollowUpCount());
        cloned.setMaxFollowUp(flow.getMaxFollowUp());
        cloned.setVersion(flow.getVersion());
        return cloned;
    }

    private boolean isTerminalSession(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        try {
            InterviewSessionStatus status = InterviewSessionStatus.valueOf(session.getStatus());
            return !status.isActive();
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildTurnDigest(String questionNumber, String answerContent) {
        return DigestUtil.sha256Hex(normalizeQuestionNumber(questionNumber) + "|" + truncateAnswer(answerContent));
    }

    private String truncateAnswer(String answerContent) {
        if (answerContent == null) {
            return "";
        }
        return answerContent.length() <= 1000 ? answerContent : answerContent.substring(0, 1000);
    }

    private Integer extractMainQuestionNo(String questionNumber) {
        String normalized = normalizeQuestionNumber(questionNumber);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        int separatorIndex = normalized.indexOf("-F");
        String mainPart = separatorIndex > 0 ? normalized.substring(0, separatorIndex) : normalized;
        try {
            return Integer.parseInt(mainPart);
        } catch (Exception ex) {
            return null;
        }
    }

    private int resolveFollowUpCount(String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int separatorIndex = questionNumber.indexOf("-F");
        if (separatorIndex < 0 || separatorIndex + 2 >= questionNumber.length()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().toUpperCase().matches("\\d+-F\\d+");
    }

    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        if (normalized.matches("\\d+-F\\d+")) {
            String[] parts = normalized.split("-F");
            try {
                return Integer.parseInt(parts[0]) + "-F" + Integer.parseInt(parts[1]);
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }
}
