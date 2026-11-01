package com.hewei.hzyjy.xunzhi.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewRecordDO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import com.hewei.hzyjy.xunzhi.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeConfidence;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeLoadMode;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewRuntimeScoreAggregate;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 提供面试会话运行态的懒恢复与缓存重建能力，
 * 用于在 Redis 运行态缺失时按需从快照、报告和主数据中恢复题目流转与得分状态。
 *
 * @author 程序员牛肉
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionRuntimeRehydrateService {

    private static final long CACHE_EXPIRE_HOURS = 24L;
    private static final int FOLLOWER_RECHECK_TIMES = 4;
    private static final long FOLLOWER_RECHECK_MILLIS = 80L;

    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    private final InterviewSessionRuntimeLockService runtimeLockService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewRecordMapper interviewRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public InterviewSessionRuntimeView ensureRuntime(String sessionId, InterviewRuntimeLoadMode loadMode) {
        return ensureRuntime(sessionId, loadMode, InterviewRuntimeRehydrateScope.FULL_RUNTIME);
    }

    public InterviewSessionRuntimeView ensureRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        InterviewRuntimeRehydrateScope resolvedScope = scope == null ? InterviewRuntimeRehydrateScope.FULL_RUNTIME : scope;
        if (StrUtil.isBlank(sessionId)) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, null);
        }
        if (isRuntimeReady(sessionId, resolvedScope)) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
        }
        RLock lock = null;
        try {
            lock = runtimeLockService.acquire(sessionId);
            if (lock == null) {
                InterviewSessionRuntimeView reused = waitForRecoveredRuntime(sessionId, loadMode, resolvedScope);
                if (reused != null) {
                    return reused;
                }
                lock = runtimeLockService.acquire(sessionId, FOLLOWER_RECHECK_MILLIS);
            }
            if (isRuntimeReady(sessionId, resolvedScope)) {
                return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
            }
            if (lock == null) {
                log.warn(
                "Failed to acquire runtime rehydrate lock after waiting, sessionId={}",
                sessionId);

                return buildView(
                loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, getRuntimeSnapshot(sessionId));
            }
            InterviewSessionRuntimeView rebuilt = rebuildRuntime(sessionId, loadMode, resolvedScope);
            if (rebuilt != null) {
                return rebuilt;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Failed to ensure runtime, sessionId={}", sessionId, ex);
        } finally {
            runtimeLockService.release(lock);
        }
        return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, getRuntimeSnapshot(sessionId));
    }

    public InterviewRecordDO getLatestRecord(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getSessionId, sessionId)
                .eq(InterviewRecordDO::getDelFlag, 0)
                .orderByDesc(InterviewRecordDO::getUpdateTime)
                .last("limit 1");
        return interviewRecordMapper.selectOne(queryWrapper);
    }

    public InterviewSessionRuntimeSnapshot getRuntimeSnapshot(String sessionId) {
        return runtimeSnapshotService.findSnapshot(sessionId).orElse(null);
    }

    private InterviewSessionRuntimeView buildView(
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRestoreSource restoreSource,
            InterviewRuntimeConfidence confidence,
            boolean cacheRebuilt,
            InterviewSessionRuntimeSnapshot snapshot) {
        return InterviewSessionRuntimeView.builder()
                .loadMode(loadMode)
                .restoreSource(restoreSource)
                .confidence(confidence)
                .cacheRebuilt(cacheRebuilt)
                .hotSnapshot(snapshot == null ? null : runtimeSnapshotService.findHotSnapshot(snapshot.getSessionId()).orElse(null))
                .coldSnapshot(snapshot == null ? null : runtimeSnapshotService.findColdSnapshot(snapshot.getSessionId()).orElse(null))
                .snapshot(snapshot)
                .build();
    }

    private InterviewSessionRuntimeView rebuildRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        InterviewSessionRuntimeSnapshot snapshot = getRuntimeSnapshot(sessionId);
        if (snapshot != null && canRehydrateFromSnapshot(snapshot, scope)) {
            writeSnapshotToCache(sessionId, snapshot, scope);
            return buildView(
                    loadMode,
                    InterviewRuntimeRestoreSource.RUNTIME_SNAPSHOT,
                    snapshot.getRebuildConfidence() == null ? InterviewRuntimeConfidence.EXACT : snapshot.getRebuildConfidence(),
                    true,
                    snapshot
            );
        }

        InterviewRecordDO record = getLatestRecord(sessionId);
        InterviewSession session = interviewSessionService.getBySessionId(sessionId);
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        RuntimeMaterial material = buildRuntimeMaterial(sessionId, session, question, record, snapshot);
        if (material == null) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, snapshot);
        }
        if (requiresQuestionMaterial(scope) && !hasQuestionMaterial(material.questions)
                && loadMode == InterviewRuntimeLoadMode.READ_WRITE_REQUIRED) {
            InterviewRuntimeConfidence confidence = isTerminalSession(session)
                    ? InterviewRuntimeConfidence.TERMINAL
                    : InterviewRuntimeConfidence.READ_ONLY;
            writePartialMaterial(sessionId, material, confidence == InterviewRuntimeConfidence.TERMINAL, scope);
            return buildView(loadMode, resolveRestoreSource(record, question), confidence, true, snapshot);
        }
        writePartialMaterial(sessionId, material, isTerminalSession(session), scope);
        return buildView(
                loadMode,
                resolveRestoreSource(record, question),
                material.confidence,
                true,
                snapshot
        );
    }

    private RuntimeMaterial buildRuntimeMaterial(
            String sessionId,
            InterviewSession session,
            InterviewQuestion question,
            InterviewRecordDO record,
            InterviewSessionRuntimeSnapshot snapshot) {
        if (session == null && question == null && record == null && snapshot == null) {
            return null;
        }
        RuntimeMaterial material = new RuntimeMaterial();
        material.questions = parseStringMap(question == null ? null : question.getQuestionsJson(), question == null ? null : question.getQuestions());
        material.suggestions = parseSuggestions(question, record);
        material.resumeContext = parseResumeContext(question);
        material.resumeScore = resolveResumeScore(question, snapshot, record);
        material.demeanorScore = resolveDemeanorScore(snapshot, record);
        material.demeanorDetails = snapshot == null ? null : snapshot.getDemeanorDetails();
        material.direction = resolveDirection(session, question, snapshot, record);
        material.turns = resolveTurns(sessionId, snapshot, record);
        material.followUpQuestions = deriveFollowUpQuestions(snapshot, material.turns);
        material.scoreAggregate = resolveScoreAggregate(snapshot, material.turns, record);
        material.flow = resolveFlow(session, material.questions, material.turns, snapshot);
        material.confidence = resolveConfidence(session, material.questions, material.flow);
        return material;
    }

    private InterviewSessionRuntimeView waitForRecoveredRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        for (int attempt = 0; attempt < FOLLOWER_RECHECK_TIMES; attempt++) {
            if (isRuntimeReady(sessionId, scope)) {
                return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(FOLLOWER_RECHECK_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return isRuntimeReady(sessionId, scope)
                ? buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId))
                : null;
    }

    private boolean canRehydrateFromSnapshot(
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRuntimeRehydrateScope scope) {
        if (snapshot == null) {
            return false;
        }
        if (scope.includesQuestionMaterial() && !hasQuestionMaterial(snapshot.getQuestions())) {
            return false;
        }
        if (scope.includesFlow() && snapshot.getFlow() == null) {
            return false;
        }
        if (scope.includesScore() && snapshot.getScoreAggregate() == null && (snapshot.getRecentTurns() == null || snapshot.getRecentTurns().isEmpty())) {
            return false;
        }
        if (scope.includesTurns() && (snapshot.getRecentTurns() == null || snapshot.getRecentTurns().isEmpty())
                && (snapshot.getArchiveWatermark() == null || snapshot.getArchiveWatermark() <= 0)) {
            return false;
        }
        if (scope.includesSuggestionMaterial() && (snapshot.getSuggestions() == null || snapshot.getSuggestions().isEmpty())) {
            return false;
        }
        if (scope.includesResumeMaterial()
                && snapshot.getResumeContext() == null
                && snapshot.getResumeScore() == null
                && StrUtil.isBlank(snapshot.getDirection())
                && snapshot.getDemeanorScore() == null
                && snapshot.getDemeanorDetails() == null) {
            return false;
        }
        return true;
    }

    private boolean requiresQuestionMaterial(InterviewRuntimeRehydrateScope scope) {
        return scope.includesQuestionMaterial() || scope.includesFlow();
    }

    private boolean isRuntimeReady(String sessionId, InterviewRuntimeRehydrateScope scope) {
        return switch (scope) {
            case FLOW_ONLY -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId));
            case SCORE_ONLY -> hasValue(InterviewCacheKeys.sessionScore(sessionId));
            case PLAYBACK_ONLY -> hasListEntries(InterviewCacheKeys.turns(sessionId));
            case MATERIAL_ONLY -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && (hasHashEntries(InterviewCacheKeys.suggestions(sessionId))
                    || hasValue(InterviewCacheKeys.resumeScore(sessionId))
                    || hasValue(InterviewCacheKeys.direction(sessionId))
                    || hasJsonValue(InterviewCacheKeys.resumeContext(sessionId)));
            case HOT_RUNTIME -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId))
                    && (hasValue(InterviewCacheKeys.sessionScore(sessionId)) || hasListEntries(InterviewCacheKeys.turns(sessionId)));
            case FULL_RUNTIME -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId))
                    && (hasValue(InterviewCacheKeys.sessionScore(sessionId)) || hasListEntries(InterviewCacheKeys.turns(sessionId)))
                    && (hasHashEntries(InterviewCacheKeys.suggestions(sessionId))
                    || hasValue(InterviewCacheKeys.resumeScore(sessionId))
                    || hasValue(InterviewCacheKeys.direction(sessionId))
                    || hasJsonValue(InterviewCacheKeys.resumeContext(sessionId)));
        };
    }

    private void writeSnapshotToCache(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRuntimeRehydrateScope scope) {
        if (snapshot == null) {
            return;
        }
        List<InterviewTurnLog> turns = Collections.emptyList();
        if (scope.includesTurns() || scope.includesScore()) {
            turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
            if (turns == null || turns.isEmpty()) {
                turns = snapshot.getRecentTurns();
            }
        }
        if (scope.includesQuestionMaterial()) {
            writeHash(InterviewCacheKeys.questions(sessionId), snapshot.getQuestions());
        }
        if (scope.includesSuggestionMaterial()) {
            writeHash(InterviewCacheKeys.suggestions(sessionId), snapshot.getSuggestions());
        }
        if (scope.includesResumeMaterial()) {
            writeJsonValue(InterviewCacheKeys.resumeContext(sessionId), snapshot.getResumeContext());
            writeValue(InterviewCacheKeys.resumeScore(sessionId), snapshot.getResumeScore());
            writeValue(InterviewCacheKeys.direction(sessionId), snapshot.getDirection());
            writeValue(InterviewCacheKeys.demeanorScore(sessionId), snapshot.getDemeanorScore());
            writeDemeanorDetails(sessionId, snapshot.getDemeanorDetails());
        }
        if (scope.includesFlow()) {
            writeFlow(sessionId, snapshot.getFlow());
        }
        if (scope.includesFollowUpQuestions()) {
            writeHash(InterviewCacheKeys.followUpQuestions(sessionId), snapshot.getFollowUpQuestions());
        }
        if (scope.includesTurns()) {
            writeTurns(sessionId, turns);
        }
        if (scope.includesScore()) {
            writeScoreAggregate(sessionId, snapshot.getScoreAggregate(), turns);
        }
        if (scope.includesRequestIds()) {
            writeRequestIds(sessionId, turns);
        }
    }

    private void writePartialMaterial(
            String sessionId,
            RuntimeMaterial material,
            boolean terminal,
            InterviewRuntimeRehydrateScope scope) {
        if (scope.includesQuestionMaterial()) {
            writeHash(InterviewCacheKeys.questions(sessionId), material.questions);
        }
        if (scope.includesSuggestionMaterial()) {
            writeHash(InterviewCacheKeys.suggestions(sessionId), material.suggestions);
        }
        if (scope.includesResumeMaterial()) {
            writeJsonValue(InterviewCacheKeys.resumeContext(sessionId), material.resumeContext);
            writeValue(InterviewCacheKeys.resumeScore(sessionId), material.resumeScore);
            writeValue(InterviewCacheKeys.direction(sessionId), material.direction);
            writeValue(InterviewCacheKeys.demeanorScore(sessionId), material.demeanorScore);
            writeDemeanorDetails(sessionId, material.demeanorDetails);
        }
        if (scope.includesFollowUpQuestions()) {
            writeHash(InterviewCacheKeys.followUpQuestions(sessionId), material.followUpQuestions);
        }
        if (scope.includesTurns()) {
            writeTurns(sessionId, material.turns);
        }
        if (scope.includesScore()) {
            writeScoreAggregate(sessionId, material.scoreAggregate, material.turns);
        }
        if (scope.includesRequestIds()) {
            writeRequestIds(sessionId, material.turns);
        }
        if (scope.includesFlow()) {
            if (terminal) {
                writeFlow(sessionId, buildCompletedFlow(material.questions == null ? 0 : material.questions.size()));
                return;
            }
            writeFlow(sessionId, material.flow);
        }
    }

    private boolean hasHashEntries(String key) {
        Long size = stringRedisTemplate.opsForHash().size(key);
        return size != null && size > 0;
    }

    private boolean hasListEntries(String key) {
        Long size = stringRedisTemplate.opsForList().size(key);
        return size != null && size > 0;
    }

    private boolean hasValue(String key) {
        return StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key));
    }

    private boolean hasJsonValue(String key) {
        return StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key));
    }

    private void writeHash(String key, Map<String, String> values) {
        stringRedisTemplate.delete(key);
        if (values == null || values.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private void writeTurns(String sessionId, List<InterviewTurnLog> turns) {
        String key = InterviewCacheKeys.turns(sessionId);
        stringRedisTemplate.delete(key);
        if (turns == null || turns.isEmpty()) {
            return;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn != null) {
                stringRedisTemplate.opsForList().rightPush(key, JSON.toJSONString(turn));
            }
        }
        stringRedisTemplate.expire(key, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private void writeFlow(String sessionId, InterviewFlowState flow) {
        if (flow == null) {
            return;
        }
        // restoreInterviewFlow 内部为 EXISTS 原子守护：仅在 flow 缺失时写入，
        // 已存在时保留当前运行态，禁止盲写覆盖并发推进。
        interviewQuestionCacheService.restoreInterviewFlow(sessionId, flow);
    }

    private void writeValue(String key, Object value) {
        stringRedisTemplate.delete(key);
        if (value == null) {
            return;
        }
        String payload = String.valueOf(value);
        if (StrUtil.isBlank(payload)) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key, payload, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private void writeJsonValue(String key, Object value) {
        stringRedisTemplate.delete(key);
        if (value == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private void writeDemeanorDetails(String sessionId, DemeanorScoreDTO detail) {
        if (detail == null) {
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorPanic(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorSeriousness(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorEmoticon(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorComposite(sessionId));
            return;
        }
        writeValue(InterviewCacheKeys.demeanorPanic(sessionId), detail.getPanicLevel());
        writeValue(InterviewCacheKeys.demeanorSeriousness(sessionId), detail.getSeriousnessLevel());
        writeValue(InterviewCacheKeys.demeanorEmoticon(sessionId), detail.getEmoticonHandling());
        writeValue(InterviewCacheKeys.demeanorComposite(sessionId), detail.getCompositeScore());
    }

    private void writeScoreAggregate(String sessionId, InterviewRuntimeScoreAggregate aggregate, List<InterviewTurnLog> turns) {
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScore(sessionId));
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScoreSum(sessionId));
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScoreCount(sessionId));
        int totalScore = aggregate == null || aggregate.getTotalScore() == null
                ? interviewQuestionCacheService.getSessionTotalScore(sessionId)
                : aggregate.getTotalScore();
        int scoreSum = aggregate == null || aggregate.getScoreSum() == null ? deriveScoreSum(turns) : aggregate.getScoreSum();
        int scoreCount = aggregate == null || aggregate.getScoreCount() == null ? deriveScoreCount(turns) : aggregate.getScoreCount();
        writeValue(InterviewCacheKeys.sessionScore(sessionId), totalScore);
        writeValue(InterviewCacheKeys.sessionScoreSum(sessionId), scoreSum);
        writeValue(InterviewCacheKeys.sessionScoreCount(sessionId), scoreCount);
    }

    private void writeRequestIds(String sessionId, List<InterviewTurnLog> turns) {
        String answerKey = InterviewCacheKeys.answerRequest(sessionId);
        String turnKey = InterviewCacheKeys.turnRequest(sessionId);
        stringRedisTemplate.delete(answerKey);
        stringRedisTemplate.delete(turnKey);
        if (turns == null || turns.isEmpty()) {
            return;
        }
        List<String> requestIds = turns.stream()
                .map(InterviewTurnLog::getRequestId)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
        if (requestIds.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForSet().add(answerKey, requestIds.toArray(new String[0]));
        stringRedisTemplate.opsForSet().add(turnKey, requestIds.toArray(new String[0]));
        stringRedisTemplate.expire(answerKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        stringRedisTemplate.expire(turnKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private int deriveScoreSum(List<InterviewTurnLog> turns) {
        if (turns == null) {
            return 0;
        }
        int sum = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            sum += turn.getScore();
        }
        return sum;
    }

    private int deriveScoreCount(List<InterviewTurnLog> turns) {
        if (turns == null) {
            return 0;
        }
        int count = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            count++;
        }
        return count;
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

    private Map<String, String> parseSuggestions(InterviewQuestion question, InterviewRecordDO record) {
        Map<String, String> suggestions = parseStringMap(
                question == null ? null : question.getSuggestionsJson(),
                question == null ? null : question.getSuggestions()
        );
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        Map<String, String> parsedFromRecord = new LinkedHashMap<>();
        if (record == null || StrUtil.isBlank(record.getInterviewSuggestions())) {
            return parsedFromRecord;
        }
        String[] segments = record.getInterviewSuggestions().split(";");
        int order = 1;
        for (String segment : segments) {
            if (StrUtil.isNotBlank(segment)) {
                parsedFromRecord.put(String.valueOf(order++), segment.trim());
            }
        }
        return parsedFromRecord;
    }

    private Map<String, Object> parseResumeContext(InterviewQuestion question) {
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

    private Integer resolveResumeScore(
            InterviewQuestion question,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        if (snapshot != null && snapshot.getResumeScore() != null) {
            return snapshot.getResumeScore();
        }
        if (question != null && question.getResumeScore() != null) {
            return question.getResumeScore();
        }
        return record == null ? null : record.getResumeScore();
    }

    private Integer resolveDemeanorScore(InterviewSessionRuntimeSnapshot snapshot, InterviewRecordDO record) {
        if (snapshot != null && snapshot.getDemeanorScore() != null) {
            return snapshot.getDemeanorScore();
        }
        if (record == null || StrUtil.isBlank(record.getSessionSnapshotJson())) {
            return null;
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(record.getSessionSnapshotJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Map<String, Object> radar = parsed == null ? null : asObjectMap(parsed.get("radar"));
            return radar == null ? null : asInteger(radar.get("demeanorEvaluation"));
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveDirection(
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        if (snapshot != null && StrUtil.isNotBlank(snapshot.getDirection())) {
            return snapshot.getDirection();
        }
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        if (session != null && StrUtil.isNotBlank(session.getInterviewType())) {
            return session.getInterviewType().trim();
        }
        return record == null ? null : record.getInterviewDirection();
    }

    private List<InterviewTurnLog> resolveTurns(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        List<InterviewTurnLog> turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
        if (turns != null && !turns.isEmpty()) {
            return new ArrayList<>(turns);
        }
        if (snapshot != null && snapshot.getRecentTurns() != null && !snapshot.getRecentTurns().isEmpty()) {
            return new ArrayList<>(snapshot.getRecentTurns());
        }
        return parseTurnsFromRecord(record);
    }

    private List<InterviewTurnLog> parseTurnsFromRecord(InterviewRecordDO record) {
        if (record == null || StrUtil.isBlank(record.getSessionSnapshotJson())) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(record.getSessionSnapshotJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object turns = parsed == null ? null : parsed.get("turns");
            if (turns == null) {
                return new ArrayList<>();
            }
            List<InterviewTurnLog> parsedTurns = JSON.parseArray(JSON.toJSONString(turns), InterviewTurnLog.class);
            return parsedTurns == null ? new ArrayList<>() : new ArrayList<>(parsedTurns);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> deriveFollowUpQuestions(
            InterviewSessionRuntimeSnapshot snapshot,
            List<InterviewTurnLog> turns) {
        if (snapshot != null && snapshot.getFollowUpQuestions() != null && !snapshot.getFollowUpQuestions().isEmpty()) {
            return new LinkedHashMap<>(snapshot.getFollowUpQuestions());
        }
        Map<String, String> followUpQuestions = new LinkedHashMap<>();
        if (turns == null) {
            return followUpQuestions;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn == null || StrUtil.isBlank(turn.getNextQuestionNumber()) || StrUtil.isBlank(turn.getNextQuestion())) {
                continue;
            }
            String questionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
            if (questionNumber != null && questionNumber.contains("-F")) {
                followUpQuestions.put(questionNumber, turn.getNextQuestion().trim());
            }
        }
        return followUpQuestions;
    }

    private InterviewRuntimeScoreAggregate resolveScoreAggregate(
            InterviewSessionRuntimeSnapshot snapshot,
            List<InterviewTurnLog> turns,
            InterviewRecordDO record) {
        if (snapshot != null && snapshot.getScoreAggregate() != null) {
            return snapshot.getScoreAggregate();
        }
        InterviewRuntimeScoreAggregate aggregate = new InterviewRuntimeScoreAggregate();
        aggregate.setScoreSum(deriveScoreSum(turns));
        aggregate.setScoreCount(deriveScoreCount(turns));
        if (record != null && record.getInterviewScore() != null) {
            aggregate.setTotalScore(record.getInterviewScore());
        } else if (aggregate.getScoreCount() != null && aggregate.getScoreCount() > 0) {
            aggregate.setTotalScore((int) Math.round((double) aggregate.getScoreSum() / aggregate.getScoreCount()));
        } else {
            aggregate.setTotalScore(0);
        }
        return aggregate;
    }

    private InterviewFlowState resolveFlow(
            InterviewSession session,
            Map<String, String> questions,
            List<InterviewTurnLog> turns,
            InterviewSessionRuntimeSnapshot snapshot) {
        if (snapshot != null && snapshot.getFlow() != null) {
            return snapshot.getFlow();
        }
        int totalQuestions = questions == null ? 0 : questions.size();
        if (isTerminalSession(session)) {
            return buildCompletedFlow(totalQuestions);
        }
        if (turns == null || turns.isEmpty()) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        InterviewTurnLog latest = turns.get(turns.size() - 1);
        if (latest == null || Boolean.TRUE.equals(latest.getFinished())) {
            return buildCompletedFlow(totalQuestions);
        }
        String nextQuestionNumber = normalizeQuestionNumber(latest.getNextQuestionNumber());
        if (StrUtil.isBlank(nextQuestionNumber)) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        return buildFlow(nextQuestionNumber, totalQuestions);
    }

    private InterviewRuntimeConfidence resolveConfidence(
            InterviewSession session,
            Map<String, String> questions,
            InterviewFlowState flow) {
        if (isTerminalSession(session)) {
            return InterviewRuntimeConfidence.TERMINAL;
        }
        if (hasQuestionMaterial(questions) && flow != null) {
            return InterviewRuntimeConfidence.DERIVED;
        }
        return hasQuestionMaterial(questions) ? InterviewRuntimeConfidence.DERIVED : InterviewRuntimeConfidence.READ_ONLY;
    }

    private InterviewRuntimeRestoreSource resolveRestoreSource(InterviewRecordDO record, InterviewQuestion question) {
        if (question != null) {
            return InterviewRuntimeRestoreSource.SESSION_QUESTION;
        }
        if (record != null) {
            return InterviewRuntimeRestoreSource.INTERVIEW_RECORD;
        }
        return InterviewRuntimeRestoreSource.NONE;
    }

    private boolean hasQuestionMaterial(Map<String, String> questions) {
        return questions != null && !questions.isEmpty();
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

    private InterviewFlowState buildFlow(String questionNumber, int totalQuestions) {
        InterviewFlowState flow = new InterviewFlowState();
        Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
        flow.setStatus(questionNumber != null && questionNumber.contains("-F") ? "FOLLOW_UP" : "ASKING");
        flow.setCurrentIndex(mainQuestionNo == null ? 0 : Math.max(mainQuestionNo - 1, 0));
        flow.setCurrentQuestionNumber(normalizeQuestionNumber(questionNumber));
        flow.setTotalQuestions(Math.max(totalQuestions, 0));
        flow.setFollowUpCount(resolveFollowUpCount(questionNumber));
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
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
        if (StrUtil.isBlank(questionNumber) || !questionNumber.toUpperCase().matches("\\d+-F\\d+")) {
            return 0;
        }
        int separatorIndex = questionNumber.indexOf("-F");
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapValue.forEach((key, item) -> {
                if (key != null) {
                    mapped.put(String.valueOf(key), item);
                }
            });
            return mapped;
        }
        return value == null ? null : JSON.parseObject(JSON.toJSONString(value), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static final class RuntimeMaterial {
        private Map<String, String> questions = new LinkedHashMap<>();
        private Map<String, String> suggestions = new LinkedHashMap<>();
        private Map<String, Object> resumeContext = new LinkedHashMap<>();
        private Integer resumeScore;
        private Integer demeanorScore;
        private DemeanorScoreDTO demeanorDetails;
        private String direction;
        private InterviewFlowState flow;
        private InterviewRuntimeScoreAggregate scoreAggregate;
        private Map<String, String> followUpQuestions = new LinkedHashMap<>();
        private List<InterviewTurnLog> turns = new ArrayList<>();
        private InterviewRuntimeConfidence confidence = InterviewRuntimeConfidence.READ_ONLY;
    }
}
