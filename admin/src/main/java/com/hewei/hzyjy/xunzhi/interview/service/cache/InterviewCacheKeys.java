package com.hewei.hzyjy.xunzhi.interview.service.cache;

public final class InterviewCacheKeys {

    private static final String QUESTIONS_KEY = "interview:questions:session:";
    private static final String SUGGESTIONS_KEY = "interview:suggestions:session:";
    private static final String RESUME_SCORE_KEY = "interview:resume_score:session:";
    private static final String RESUME_CONTEXT_KEY = "interview:resume_context:session:";
    private static final String DEMEANOR_SCORE_KEY = "interview:demeanor_score:session:";
    private static final String DIRECTION_KEY = "interview:direction:session:";
    private static final String FLOW_KEY = "interview:flow:session:{%s}";
    private static final String FOLLOW_UP_QUESTIONS_KEY = "interview:follow_up_questions:session:";
    private static final String ANSWER_REQUEST_KEY = "interview:answer:req:session:";
    private static final String TURNS_KEY = "interview:turns:session:{%s}";
    private static final String TURN_REQUEST_KEY = "interview:turn:req:session:";
    private static final String SESSION_SCORE_KEY = "interview:score:session:{%s}";
    private static final String SESSION_SCORE_SUM_KEY = "interview:score_sum:session:{%s}";
    private static final String SESSION_SCORE_COUNT_KEY = "interview:score_count:session:{%s}";
    private static final String COMMITTED_ANSWER_KEY = "interview:answer:committed:session:{%s}";

    // 旧键名（无 {sessionId} hash tag）：键形制切换时用于兼容存量会话数据，仅迁移/读取用。
    private static final String LEGACY_FLOW_KEY = "interview:flow:session:";
    private static final String LEGACY_TURNS_KEY = "interview:turns:session:";
    private static final String LEGACY_SESSION_SCORE_KEY = "interview:score:session:";
    private static final String LEGACY_SESSION_SCORE_SUM_KEY = "interview:score_sum:session:";
    private static final String LEGACY_SESSION_SCORE_COUNT_KEY = "interview:score_count:session:";
    private static final String DEMEANOR_PANIC_PREFIX = "demeanor:panic:";
    private static final String DEMEANOR_SERIOUSNESS_PREFIX = "demeanor:seriousness:";
    private static final String DEMEANOR_EMOTICON_PREFIX = "demeanor:emoticon:";
    private static final String DEMEANOR_COMPOSITE_PREFIX = "demeanor:composite:";

    private InterviewCacheKeys() {
    }

    public static String questions(String sessionId) {
        return QUESTIONS_KEY + sessionId;
    }

    public static String suggestions(String sessionId) {
        return SUGGESTIONS_KEY + sessionId;
    }

    public static String resumeScore(String sessionId) {
        return RESUME_SCORE_KEY + sessionId;
    }

    public static String resumeContext(String sessionId) {
        return RESUME_CONTEXT_KEY + sessionId;
    }

    public static String demeanorScore(String sessionId) {
        return DEMEANOR_SCORE_KEY + sessionId;
    }

    public static String direction(String sessionId) {
        return DIRECTION_KEY + sessionId;
    }

    public static String flow(String sessionId) {
        return String.format(FLOW_KEY, sessionId);
    }

    public static String followUpQuestions(String sessionId) {
        return FOLLOW_UP_QUESTIONS_KEY + sessionId;
    }

    public static String answerRequest(String sessionId) {
        return ANSWER_REQUEST_KEY + sessionId;
    }

    public static String turns(String sessionId) {
        return String.format(TURNS_KEY, sessionId);
    }

    public static String turnRequest(String sessionId) {
        return TURN_REQUEST_KEY + sessionId;
    }

    public static String sessionScore(String sessionId) {
        return String.format(SESSION_SCORE_KEY, sessionId);
    }

    public static String sessionScoreSum(String sessionId) {
        return String.format(SESSION_SCORE_SUM_KEY, sessionId);
    }

    public static String sessionScoreCount(String sessionId) {
        return String.format(SESSION_SCORE_COUNT_KEY, sessionId);
    }

    /**
     * 答题原子提交的幂等记录 Hash（field=requestId，value=完整提交记录 JSON）。
     */
    public static String committedAnswer(String sessionId) {
        return String.format(COMMITTED_ANSWER_KEY, sessionId);
    }

    public static String legacyFlow(String sessionId) {
        return LEGACY_FLOW_KEY + sessionId;
    }

    public static String legacyTurns(String sessionId) {
        return LEGACY_TURNS_KEY + sessionId;
    }

    public static String legacySessionScore(String sessionId) {
        return LEGACY_SESSION_SCORE_KEY + sessionId;
    }

    public static String legacySessionScoreSum(String sessionId) {
        return LEGACY_SESSION_SCORE_SUM_KEY + sessionId;
    }

    public static String legacySessionScoreCount(String sessionId) {
        return LEGACY_SESSION_SCORE_COUNT_KEY + sessionId;
    }

    public static String demeanorPanic(String sessionId) {
        return DEMEANOR_PANIC_PREFIX + sessionId;
    }

    public static String demeanorSeriousness(String sessionId) {
        return DEMEANOR_SERIOUSNESS_PREFIX + sessionId;
    }

    public static String demeanorEmoticon(String sessionId) {
        return DEMEANOR_EMOTICON_PREFIX + sessionId;
    }

    public static String demeanorComposite(String sessionId) {
        return DEMEANOR_COMPOSITE_PREFIX + sessionId;
    }
}

