package com.hewei.hzyjy.xunzhi.interview.service.impl;

import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRadarService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewScoreService;
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitRequest;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 直连真实 Redis（docker waimai-redis, 127.0.0.1:6379）验证答题原子提交 Lua 脚本行为。
 * <p>
 * 不经 Spring 全量上下文（避免 Mongo/MySQL 依赖）：手动构造 Lettuce 连接，
 * Mock 掉 InterviewQuestionCacheServiceImpl 的数据库侧协作者，commitAnswerAtomically 执行真实脚本。
 * <p>
 * 前置条件：本地 6379 端口有 Redis 运行；不可用时整个测试类被跳过（Abort）。
 * Redis Cluster 的真实 CROSSSLOT 校验需集群环境，本测试以"六键 hash tag 一致"的静态断言覆盖同 slot 前提。
 */
class InterviewAnswerCommitIntegrationTest {

    private static StringRedisTemplate redis;
    private InterviewQuestionCacheServiceImpl cacheService;
    private String sessionId;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable at 127.0.0.1:6379, integration test skipped: " + ex.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        sessionId = "it-" + UUID.randomUUID();
        cacheService = new InterviewQuestionCacheServiceImpl(
                redis,
                mock(InterviewQuestionService.class),
                mock(InterviewScoreService.class),
                mock(InterviewRadarService.class)
        );
    }

    @AfterEach
    void tearDown() {
        List.of(
                InterviewCacheKeys.flow(sessionId),
                InterviewCacheKeys.sessionScoreSum(sessionId),
                InterviewCacheKeys.sessionScoreCount(sessionId),
                InterviewCacheKeys.sessionScore(sessionId),
                InterviewCacheKeys.turns(sessionId),
                InterviewCacheKeys.committedAnswer(sessionId),
                InterviewCacheKeys.legacyFlow(sessionId),
                InterviewCacheKeys.legacyTurns(sessionId),
                InterviewCacheKeys.legacySessionScoreSum(sessionId),
                InterviewCacheKeys.legacySessionScoreCount(sessionId),
                InterviewCacheKeys.legacySessionScore(sessionId)
        ).forEach(redis::delete);
    }

    @Test
    void sameRequestIdCommitsExactlyOnce() {
        initFlow(3);
        String requestId = "req-" + UUID.randomUUID();

        AnswerCommitResult first = commitMain(requestId, 85);
        assertEquals(AnswerCommitResult.Code.COMMITTED, first.getCode());

        AnswerCommitResult second = commitMain(requestId, 85);
        assertEquals(AnswerCommitResult.Code.REPLAYED, second.getCode());

        assertEquals(1, scoreCount());
        assertEquals("85", avg());
        assertEquals(1, turnCount());
        assertNotNull(committedRecord(requestId));
    }

    @Test
    void versionConflictHasZeroSideEffects() {
        initFlow(3);
        int versionBefore = flowVersion();
        String requestId = "req-" + UUID.randomUUID();

        AnswerCommitResult conflict = commitWithExpectedVersion(versionBefore - 1, 85, requestId);
        assertEquals(AnswerCommitResult.Code.VERSION_CONFLICT, conflict.getCode());

        assertEquals(versionBefore, flowVersion());
        assertNull(redis.opsForValue().get(sumKey()));
        assertEquals(0, turnCount());
        // 冲突请求的幂等记录必须不存在（同 requestId 断言，而非另取随机 ID）
        assertNull(redis.opsForHash().get(InterviewCacheKeys.committedAnswer(sessionId), requestId));
    }

    @Test
    void mainQuestionUpdatesFlowScoreAndTurnsAtomically() {
        initFlow(3);
        int versionBefore = flowVersion();

        AnswerCommitResult result = commitMain("req-" + UUID.randomUUID(), 85);
        assertEquals(AnswerCommitResult.Code.COMMITTED, result.getCode());
        assertEquals(Long.valueOf(versionBefore + 1), result.getFlowVersion());

        assertEquals(versionBefore + 1, flowVersion());
        assertEquals("ASKING", flowStatus());
        assertEquals("2", flowCurrentQuestionNumber());
        assertEquals(1, scoreCount());
        assertEquals("85", avg());
        assertEquals(1, turnCount());
    }

    @Test
    void followUpAdvancesFlowWithoutScoring() {
        initFlow(3);
        commitMain("req-main-" + UUID.randomUUID(), 80);
        assertEquals(1, scoreCount());

        AnswerCommitResult followUp = commitFollowUp("req-follow-" + UUID.randomUUID());
        assertEquals(AnswerCommitResult.Code.COMMITTED, followUp.getCode());

        assertEquals(1, scoreCount());
        assertEquals("80", avg());
        assertEquals("FOLLOW_UP", flowStatus());
        assertEquals("2-F1", flowCurrentQuestionNumber());
        assertEquals(2, turnCount());
    }

    @Test
    void overflowPrecheckRejectsBeforeWritingFlow() {
        initFlow(3);
        int versionBefore = flowVersion();
        // 把 sum 顶到 Long.MAX_VALUE - 5；score=10 时上限为 Long.MAX_VALUE - 10 → 必溢出
        redis.opsForValue().set(sumKey(), String.valueOf(Long.MAX_VALUE - 5));

        AnswerCommitResult result = commitMain("req-" + UUID.randomUUID(), 10);
        assertEquals(AnswerCommitResult.Code.INVALID_ARGUMENT, result.getCode());

        assertEquals(versionBefore, flowVersion());
        assertEquals(String.valueOf(Long.MAX_VALUE - 5), redis.opsForValue().get(sumKey()));
        assertEquals(0, turnCount());
    }

    @Test
    void allSixScriptKeysShareTheSameHashTag() {
        String sid = sessionId;
        String tag = "{" + sid + "}";
        List<String> keys = List.of(
                InterviewCacheKeys.flow(sid),
                InterviewCacheKeys.sessionScoreSum(sid),
                InterviewCacheKeys.sessionScoreCount(sid),
                InterviewCacheKeys.sessionScore(sid),
                InterviewCacheKeys.committedAnswer(sid),
                InterviewCacheKeys.turns(sid)
        );
        // 六个键必须包含同一 {sessionId} hash tag，否则 Redis Cluster 下 EVAL 会 CROSSSLOT 拒绝执行。
        for (String key : keys) {
            assertTrue(key.contains(tag), "key must carry hash tag: " + key);
        }
        // 各自 slot 计算一致（tag 相同即同 slot，此处再显式断言）
        assertEquals(1, keys.stream().map(key -> key.substring(key.indexOf(tag))).distinct().count());
    }

    // ==================== helpers ====================

    private void initFlow(int totalQuestions) {
        cacheService.initInterviewFlow(sessionId, totalQuestions);
    }

    private AnswerCommitResult commitMain(String requestId, int score) {
        return commit(buildMainRequest(requestId, score, flowVersion()));
    }

    private AnswerCommitResult commitWithExpectedVersion(int expectedVersion, int score, String requestId) {
        return commit(buildMainRequest(requestId, score, expectedVersion));
    }

    private AnswerCommitResult commitFollowUp(String requestId) {
        return commit(buildFollowUpRequest(requestId));
    }

    private AnswerCommitResult commit(AnswerCommitRequest request) {
        return cacheService.commitAnswerAtomically(request);
    }

    private AnswerCommitRequest buildMainRequest(String requestId, int score, int expectedVersion) {
        return AnswerCommitRequest.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .questionNumber("1")
                .answerDigest("digest-" + requestId)
                .shouldScore(true)
                .score(score)
                .expectedVersion(expectedVersion)
                .status("ASKING")
                .currentIndex(0)
                .currentQuestionNumber("2")
                .totalQuestions(3)
                .followUpCount(0)
                .maxFollowUp(2)
                .feedback("good")
                .nextQuestionNumber("2")
                .nextQuestion("question-2")
                .finished(false)
                .isFollowUp(false)
                .currentTotalScore(0)
                .turnPayload("{\"requestId\":\"" + requestId + "\",\"questionNumber\":\"1\","
                        + "\"questionContent\":\"question-1\",\"answerContent\":\"my answer\","
                        + "\"score\":" + score + ",\"totalScore\":0,\"feedback\":\"good\","
                        + "\"followUpNeeded\":false,\"isFollowUp\":false,\"followUpCount\":0,"
                        + "\"nextQuestionNumber\":\"2\",\"nextQuestion\":\"question-2\",\"finished\":false}")
                .build();
    }

    private AnswerCommitRequest buildFollowUpRequest(String requestId) {
        return AnswerCommitRequest.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .questionNumber("1")
                .answerDigest("digest-" + requestId)
                .shouldScore(false)
                .score(80)
                .expectedVersion(flowVersion())
                .status("FOLLOW_UP")
                .currentIndex(0)
                .currentQuestionNumber("2-F1")
                .totalQuestions(3)
                .followUpCount(1)
                .maxFollowUp(2)
                .feedback("follow up")
                .nextQuestionNumber("2-F1")
                .nextQuestion("follow-up-question")
                .finished(false)
                .isFollowUp(true)
                .currentTotalScore(80)
                .turnPayload("{\"requestId\":\"" + requestId + "\",\"questionNumber\":\"1\","
                        + "\"questionContent\":\"question-1\",\"answerContent\":\"my answer\","
                        + "\"score\":80,\"totalScore\":0,\"feedback\":\"follow up\","
                        + "\"followUpNeeded\":true,\"isFollowUp\":false,\"followUpCount\":0,"
                        + "\"nextQuestionNumber\":\"2-F1\",\"nextQuestion\":\"follow-up-question\",\"finished\":false}")
                .build();
    }

    private int flowVersion() {
        Object version = redis.opsForHash().get(InterviewCacheKeys.flow(sessionId), "version");
        return version == null ? -1 : Integer.parseInt(version.toString());
    }

    private String flowStatus() {
        Object status = redis.opsForHash().get(InterviewCacheKeys.flow(sessionId), "status");
        return status == null ? null : status.toString();
    }

    private String flowCurrentQuestionNumber() {
        Object cqn = redis.opsForHash().get(InterviewCacheKeys.flow(sessionId), "currentQuestionNumber");
        return cqn == null ? null : cqn.toString();
    }

    private long scoreCount() {
        // 纯读：INCRBY 0 在键不存在时会创建 0 值键（写副作用），不得用于计数读取。
        String count = redis.opsForValue().get(InterviewCacheKeys.sessionScoreCount(sessionId));
        return count == null ? 0L : Long.parseLong(count);
    }

    private String avg() {
        return redis.opsForValue().get(InterviewCacheKeys.sessionScore(sessionId));
    }

    private long turnCount() {
        Long size = redis.opsForList().size(InterviewCacheKeys.turns(sessionId));
        return size == null ? 0L : size;
    }

    private Object committedRecord(String requestId) {
        return redis.opsForHash().get(InterviewCacheKeys.committedAnswer(sessionId), requestId);
    }

    private String sumKey() {
        return InterviewCacheKeys.sessionScoreSum(sessionId);
    }
}
