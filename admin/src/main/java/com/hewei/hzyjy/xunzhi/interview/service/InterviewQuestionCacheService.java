package com.hewei.hzyjy.xunzhi.interview.service;

import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;

import java.util.List;
import java.util.Map;

/**
 * 面试题缓存服务接口
 */
public interface InterviewQuestionCacheService {
    
    /**
     * 缓存面试题到Redis
     * @param sessionId 会话ID
     * @param questions 面试题列表
     */
    void cacheInterviewQuestions(String sessionId, List<String> questions);
    
    /**
     * 缓存面试建议到Redis
     * @param sessionId 会话ID
     * @param suggestions 建议列表
     */
    void cacheInterviewSuggestions(String sessionId, List<String> suggestions);
    
    /**
     * 缓存简历评分到Redis
     * @param sessionId 会话ID
     * @param resumeScore 简历评分
     */
    void cacheResumeScore(String sessionId, Integer resumeScore);
    
    /**
     * 缓存神态管理评分到Redis
     * @param sessionId 会话ID
     * @param demeanorScore 神态管理评分
     */
    void cacheDemeanorScore(String sessionId, Integer demeanorScore);
    
    /**
     * 获取会话的面试题
     * @param sessionId 会话ID
     * @return 题号和题目的映射
     */
    Map<String, String> getSessionInterviewQuestions(String sessionId);
    
    /**
     * 获取会话的面试建议
     * @param sessionId 会话ID
     * @return 建议编号和建议内容的映射
     */
    Map<String, String> getSessionInterviewSuggestions(String sessionId);
    
    /**
     * 获取会话的简历评分
     * @param sessionId 会话ID
     * @return 简历评分
     */
    Integer getSessionResumeScore(String sessionId);

    /**
     * 缓存简历抽取上下文（结构化信息）。
     * @param sessionId 会话ID
     * @param resumeContext 简历上下文
     */
    void cacheResumeContext(String sessionId, Map<String, Object> resumeContext);

    /**
     * 获取会话的简历抽取上下文（结构化信息）。
     * @param sessionId 会话ID
     * @return 简历上下文
     */
    Map<String, Object> getSessionResumeContext(String sessionId);
    
    /**
     * 获取会话的神态管理评分
     * @param sessionId 会话ID
     * @return 神态管理评分
     */
    Integer getSessionDemeanorScore(String sessionId);

    /**
     * Cache one follow-up question for the current session.
     * @param sessionId session id
     * @param questionNumber follow-up question number, such as 1-F1
     * @param questionContent question content
     */
    void cacheFollowUpQuestion(String sessionId, String questionNumber, String questionContent);

    /**
     * Get cached follow-up questions for a session.
     * @param sessionId session id
     * @return follow-up question map
     */
    Map<String, String> getSessionFollowUpQuestions(String sessionId);
    
    /**
     * 根据题号获取题目
     * @param sessionId 会话ID
     * @param questionNumber 题号
     * @return 题目内容
     */
    String getQuestionByNumber(String sessionId, String questionNumber);
    
    /**
     * 清除会话的面试题缓存
     * @param sessionId 会话ID
     */
    void clearSessionQuestions(String sessionId);
    
    /**
     * 清除会话的面试建议缓存
     * @param sessionId 会话ID
     */
    void clearSessionSuggestions(String sessionId);
    
    /**
     * 获取会话当前总分
     * @param sessionId 会话ID
     * @return 总分
     */
    Integer getSessionTotalScore(String sessionId);
    
    /**
     * 累加会话分数
     * @param sessionId 会话ID
     * @param score 本次得分
     * @return 累加后的总分
     */
    Integer addSessionScore(String sessionId, Integer score);
    
    /**
     * 重置会话分数
     * @param sessionId 会话ID
     */
    void resetSessionScore(String sessionId);
    
    /**
     * 获取雷达图数据
     * @param sessionId 会话ID
     * @return 雷达图数据
     */
    RadarChartDTO getRadarChartData(String sessionId);
    
    /**
     * 缓存神态评分详细数据到Redis
     * @param sessionId 会话ID
     * @param panicLevel 慌乱度
     * @param seriousnessLevel 严肃程度
     * @param emoticonHandling 表情处理
     * @param compositeScore 综合得分
     */
    void cacheDemeanorScoreDetails(String sessionId, Integer panicLevel, Integer seriousnessLevel, 
                                   Integer emoticonHandling, Integer compositeScore);
    
    /**
     * 获取会话神态评分详细数据
     * @param sessionId 会话ID
     * @return 神态评分详细数据
     */
    DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId);
    
    /**
     * 从数据库加载面试题到缓存
     * 优先使用JSON格式数据，如果不存在则使用List格式数据
     * @param sessionId 会话ID
     */
    void loadInterviewQuestionsFromDatabase(String sessionId);
    
    /**
     * 从数据库加载面试建议到缓存
     * 优先使用JSON格式数据，如果不存在则使用List格式数据
     * @param sessionId 会话ID
     */
    void loadInterviewSuggestionsFromDatabase(String sessionId);
    
    /**
     * 从数据库加载简历评分到缓存
     * @param sessionId 会话ID
     */
    void loadResumeScoreFromDatabase(String sessionId);
    
    /**
     * 缓存面试方向到Redis
     * @param sessionId 会话ID
     * @param interviewDirection 面试方向
     */
    void cacheInterviewDirection(String sessionId, String interviewDirection);
    
    /**
     * 获取会话的面试方向
     * @param sessionId 会话ID
     * @return 面试方向
     */
    String getSessionInterviewDirection(String sessionId);

    /**
     * Initialize interview flow state for a session.
     * @param sessionId session id
     * @param totalQuestions total main questions
     */
    void initInterviewFlow(String sessionId, Integer totalQuestions);

    /**
     * Get interview flow state.
     * @param sessionId session id
     * @return flow state, null when absent
     */
    InterviewFlowState getInterviewFlow(String sessionId);

    /**
     * Update interview flow status.
     * @param sessionId session id
     * @param status flow status
     */
    void updateInterviewFlowStatus(String sessionId, String status);

    /**
     * Increase follow-up count for current main question.
     * @param sessionId session id
     * @return updated flow state
     */
    InterviewFlowState incrementFollowUpCount(String sessionId);

    /**
     * Enter one follow-up question for the current main question.
     * @param sessionId session id
     * @param questionNumber follow-up question number
     * @return updated flow state
     */
    InterviewFlowState startFollowUpQuestion(String sessionId, String questionNumber);

    /**
     * Advance to next main question.
     * @param sessionId session id
     * @return updated flow state
     */
    InterviewFlowState advanceToNextQuestion(String sessionId);

    /**
     * Mark interview completed.
     * @param sessionId session id
     * @return updated flow state
     */
    InterviewFlowState markInterviewCompleted(String sessionId);

    /**
     * Record request id for idempotency.
     * @param sessionId session id
     * @param requestId client request id
     * @return true when this is a new request id
     */
    boolean markAnswerRequestProcessed(String sessionId, String requestId);

    /**
     * Append one interview turn log to cache.
     * @param sessionId session id
     * @param turnData turn payload
     */
    void appendInterviewTurn(String sessionId, InterviewTurnLog turnData);

    /**
     * Append one interview turn log once by requestId.
     * @param sessionId session id
     * @param turnData turn payload
     * @return true when append succeeded or duplicated by the same requestId
     */
    boolean appendInterviewTurnIfAbsent(String sessionId, InterviewTurnLog turnData);

    /**
     * List interview turn logs from cache.
     * @param sessionId session id
     * @return turn log list
     */
    List<InterviewTurnLog> getInterviewTurns(String sessionId);
}
