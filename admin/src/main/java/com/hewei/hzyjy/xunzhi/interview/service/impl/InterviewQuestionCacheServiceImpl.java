package com.hewei.hzyjy.xunzhi.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorScoreDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRadarService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewScoreService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 面试题缓存服务实现，负责面试题、建议、评分与流程状态的缓存读写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewQuestionCacheServiceImpl implements InterviewQuestionCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewScoreService interviewScoreService;
    private final InterviewRadarService interviewRadarService;
    
    /**
     * 面试题缓存键前缀。
     */
    private static final String INTERVIEW_QUESTIONS_KEY = "interview:questions:session:";
    
    /**
     * 面试建议缓存键前缀。
     */
    private static final String INTERVIEW_SUGGESTIONS_KEY = "interview:suggestions:session:";
    
    /**
     * 简历评分缓存键前缀。
     */
    private static final String RESUME_SCORE_KEY = "interview:resume_score:session:";

    /**
     * 简历上下文缓存键前缀。
     */
    private static final String RESUME_CONTEXT_KEY = "interview:resume_context:session:";
    
    /**
     * 仪态评分缓存键前缀。
     */
    private static final String DEMEANOR_SCORE_KEY = "interview:demeanor_score:session:";
    
    /**
     * 面试方向缓存键前缀。
     */
    private static final String INTERVIEW_DIRECTION_KEY = "interview:direction:session:";

    /**
     * 面试流程状态缓存键前缀。
     */
    private static final String INTERVIEW_FLOW_KEY = "interview:flow:session:";

    /**
     * 追问问题缓存键前缀。
     */
    private static final String INTERVIEW_FOLLOW_UP_QUESTIONS_KEY = "interview:follow_up_questions:session:";

    /**
     * 面试回答请求 ID（幂等）缓存键前缀。
     */
    private static final String INTERVIEW_ANSWER_REQUEST_KEY = "interview:answer:req:session:";

    /**
     * 面试轮次日志缓存键前缀。
     */
    private static final String INTERVIEW_TURNS_KEY = "interview:turns:session:";
    private static final String INTERVIEW_TURN_REQUEST_KEY = "interview:turn:req:session:";

    private static final int MAX_TURN_LOGS = 200;
    private static final int FLOW_CAS_MAX_RETRIES = 5;

    private static final String FLOW_STATUS_INIT = "INIT";
    private static final String FLOW_STATUS_ASKING = "ASKING";
    private static final String FLOW_STATUS_EVALUATING = "EVALUATING";
    private static final String FLOW_STATUS_FOLLOW_UP = "FOLLOW_UP";
    private static final String FLOW_STATUS_COMPLETED = "COMPLETED";

    private static final String FLOW_CAS_UPDATE_SCRIPT_TEXT =
            "local current = redis.call('HGET', KEYS[1], 'version') "
                    + "if current == false then current = '0' end "
                    + "if tostring(current) ~= tostring(ARGV[1]) then return 0 end "
                    + "redis.call('HSET', KEYS[1], "
                    + "'status', ARGV[2], "
                    + "'currentIndex', ARGV[3], "
                    + "'currentQuestionNumber', ARGV[4], "
                    + "'totalQuestions', ARGV[5], "
                    + "'followUpCount', ARGV[6], "
                    + "'maxFollowUp', ARGV[7], "
                    + "'version', ARGV[8]) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[9])) "
                    + "return 1";

    private static final DefaultRedisScript<Long> FLOW_CAS_UPDATE_SCRIPT = initFlowCasScript();
    
    /**
     * 缓存默认过期时间（小时）。
     */
    private static final long CACHE_EXPIRE_HOURS = 24;
    
    @Override
    public void cacheInterviewQuestions(String sessionId, List<String> questions) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            
            // 覆盖写入前先清理旧缓存，避免历史数据残留。
            stringRedisTemplate.delete(cacheKey);
            
            // 将题目列表转换为 Hash 结构（题号 -> 题目内容）。
            Map<String, String> questionMap = new HashMap<>();
            for (int i = 0; i < questions.size(); i++) {
                String questionNumber = String.valueOf(i + 1);
                questionMap.put(questionNumber, questions.get(i));
            }
            
            if (!questionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, questionMap);
                // 设置缓存过期时间。
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview questions, sessionId: {}, count: {}", sessionId, questions.size());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheInterviewSuggestions(String sessionId, List<String> suggestions) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;

            stringRedisTemplate.delete(cacheKey);

            Map<String, String> suggestionMap = new HashMap<>();
            for (int i = 0; i < suggestions.size(); i++) {
                String suggestionNumber = String.valueOf(i + 1);
                suggestionMap.put(suggestionNumber, suggestions.get(i));
            }
            
            if (!suggestionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, suggestionMap);
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview suggestions, sessionId: {}, count: {}", sessionId, suggestions.size());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheResumeScore(String sessionId, Integer resumeScore) {
        try {
            String cacheKey = RESUME_SCORE_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);

            stringRedisTemplate.opsForValue().set(cacheKey, resumeScore.toString());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, resumeScore);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheDemeanorScore(String sessionId, Integer demeanorScore) {
        try {
            String cacheKey = DEMEANOR_SCORE_KEY + sessionId;
            stringRedisTemplate.opsForValue().set(cacheKey, demeanorScore.toString());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, demeanorScore);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, String> getSessionInterviewQuestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 使用 LinkedHashMap，保证排序后遍历顺序稳定。
            Map<String, String> questionMap = new LinkedHashMap<>();
            
            // 按题号排序返回，避免 Hash 无序导致前端展示顺序混乱。
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 统一按字符串处理键，兼容不同序列化类型。
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 两个键都是数字时按数值排序（如 2 < 10）。
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 非数字键按字典序排序。
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 解析异常时回退到字符串比较，保证流程不中断。
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    questionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview questions from cache, sessionId: {}, count: {}", sessionId, questionMap.size());
            return questionMap;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    @Override
    public Integer getSessionResumeScore(String sessionId) {
        try {
            String cacheKey = RESUME_SCORE_KEY + sessionId;
            String scoreStr = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(scoreStr)) {
                return Integer.parseInt(scoreStr);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get resume score, sessionId: {}", sessionId, e);
            return null;
        }
    }

    @Override
    public void cacheResumeContext(String sessionId, Map<String, Object> resumeContext) {
        try {
            if (StrUtil.isBlank(sessionId)) {
                return;
            }
            String cacheKey = RESUME_CONTEXT_KEY + sessionId;
            if (resumeContext == null || resumeContext.isEmpty()) {
                stringRedisTemplate.delete(cacheKey);
                return;
            }
            String payload = JSON.toJSONString(resumeContext);
            stringRedisTemplate.opsForValue().set(cacheKey, payload);
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, resumeContext.keySet());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> getSessionResumeContext(String sessionId) {
        try {
            if (StrUtil.isBlank(sessionId)) {
                return new HashMap<>();
            }
            String cacheKey = RESUME_CONTEXT_KEY + sessionId;
            String payload = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isBlank(payload)) {
                return new HashMap<>();
            }
            Map<String, Object> parsed = JSON.parseObject(payload, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    @Override
    public Integer getSessionDemeanorScore(String sessionId) {
        try {
            return interviewScoreService.getSessionDemeanorScore(sessionId);
        } catch (Exception e) {
            log.error("Failed to get demeanor score, sessionId: {}", sessionId, e);
            return null;
        }
    }

    @Override
    public void cacheFollowUpQuestion(String sessionId, String questionNumber, String questionContent) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber) || StrUtil.isBlank(questionContent)) {
            return;
        }
        try {
            String cacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            stringRedisTemplate.opsForHash().put(cacheKey, questionNumber.trim(), questionContent.trim());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Cached follow-up question, sessionId: {}, questionNumber: {}", sessionId, questionNumber);
        } catch (Exception e) {
            log.error("Failed to cache follow-up question, sessionId: {}, questionNumber: {}", sessionId, questionNumber, e);
        }
    }

    @Override
    public Map<String, String> getSessionFollowUpQuestions(String sessionId) {
        Map<String, String> followUpQuestionMap = new LinkedHashMap<>();
        if (StrUtil.isBlank(sessionId)) {
            return followUpQuestionMap;
        }
        try {
            String cacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            if (rawMap == null || rawMap.isEmpty()) {
                return followUpQuestionMap;
            }
            rawMap.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                String cachedQuestionNumber = key.toString();
                String cachedQuestionContent = value.toString();
                if (StrUtil.isNotBlank(cachedQuestionNumber) && StrUtil.isNotBlank(cachedQuestionContent)) {
                    followUpQuestionMap.put(cachedQuestionNumber, cachedQuestionContent);
                }
            });
            return followUpQuestionMap;
        } catch (Exception e) {
            log.error("Failed to get follow-up questions, sessionId: {}", sessionId, e);
            return followUpQuestionMap;
        }
    }

    @Override
    public Map<String, String> getSessionInterviewSuggestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 使用 LinkedHashMap，保证排序后遍历顺序稳定。
            Map<String, String> suggestionMap = new LinkedHashMap<>();
            
            // 按编号排序返回，避免 Hash 无序导致前端展示顺序混乱。
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 统一按字符串处理键，兼容不同序列化类型。
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 两个键都是数字时按数值排序（如 2 < 10）。
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 非数字键按字典序排序。
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 解析异常时回退到字符串比较，保证流程不中断。
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    suggestionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview suggestions from cache, sessionId: {}, count: {}", sessionId, suggestionMap.size());
            return suggestionMap;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    @Override
    public String getQuestionByNumber(String sessionId, String questionNumber) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Object question = stringRedisTemplate.opsForHash().get(cacheKey, questionNumber);
            if (question == null) {
                String followUpCacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
                question = stringRedisTemplate.opsForHash().get(followUpCacheKey, questionNumber);
            }
            return question != null ? question.toString() : null;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, questionNumber, e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public void clearSessionQuestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            String followUpCacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(List.of(cacheKey, followUpCacheKey));
            log.info("Cleared interview question cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void clearSessionSuggestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);
            log.info("Cleared interview suggestion cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载面试题到缓存。
     * 优先解析 questionsJson，解析失败时回退到 questions 列表。
     */
    public void loadInterviewQuestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview question data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 优先使用 JSON 字段恢复题目（保留题号）。
            if (StrUtil.isNotBlank(question.getQuestionsJson())) {
                try {
                    Map<String, String> questionsMap = JSON.parseObject(
                        question.getQuestionsJson(), 
                        new TypeReference<LinkedHashMap<String, String>>() {}
                    );
                    
                    String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
                    stringRedisTemplate.delete(cacheKey);
                    
                    if (!questionsMap.isEmpty()) {
                        stringRedisTemplate.opsForHash().putAll(cacheKey, questionsMap);
                        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
                    }
                    
                    log.info("Interview cache service message", sessionId, questionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("Interview cache service message", e.getMessage());
                }
            }
            
            // JSON 不可用时回退到列表字段写入缓存。
            if (question.getQuestions() != null && !question.getQuestions().isEmpty()) {
                cacheInterviewQuestions(sessionId, question.getQuestions());
                log.info("Interview cache service message", sessionId, question.getQuestions().size());
            }
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载面试建议到缓存。
     * 优先解析 suggestionsJson，解析失败时回退到 suggestions 列表。
     */
    public void loadInterviewSuggestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview suggestion data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 优先使用 JSON 字段恢复建议（保留编号）。
            if (StrUtil.isNotBlank(question.getSuggestionsJson())) {
                try {
                    Map<String, String> suggestionsMap = JSON.parseObject(
                        question.getSuggestionsJson(), 
                        new TypeReference<LinkedHashMap<String, String>>() {}
                    );
                    
                    String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
                    stringRedisTemplate.delete(cacheKey);
                    
                    if (!suggestionsMap.isEmpty()) {
                        stringRedisTemplate.opsForHash().putAll(cacheKey, suggestionsMap);
                        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
                    }
                    
                    log.info("Interview cache service message", sessionId, suggestionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("Interview cache service message", e.getMessage());
                }
            }
            
            // JSON 不可用时回退到列表字段写入缓存。
            if (question.getSuggestions() != null && !question.getSuggestions().isEmpty()) {
                cacheInterviewSuggestions(sessionId, question.getSuggestions());
                log.info("Interview cache service message", sessionId, question.getSuggestions().size());
            }
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载简历评分到缓存。
     */
    public void loadResumeScoreFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null || question.getResumeScore() == null) {
                log.warn("Resume score data not found, sessionId: {}", sessionId);
                return;
            }
            
            cacheResumeScore(sessionId, question.getResumeScore());
            log.info("Interview cache service message", sessionId, question.getResumeScore());
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public Integer getSessionTotalScore(String sessionId) {
        try {
            return interviewScoreService.getSessionTotalScore(sessionId, getInterviewTurns(sessionId));
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Integer addSessionScore(String sessionId, Integer score) {
        try {
            Integer total = interviewScoreService.addSessionScore(sessionId, score);
            log.info("Interview cache service message", sessionId, score, total);
            return total;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, score, e.getMessage(), e);
            return getSessionTotalScore(sessionId);
        }
    }

    @Override
    public void resetSessionScore(String sessionId) {
        try {
            interviewScoreService.resetSessionScore(sessionId);
            log.info("Reset session score, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public RadarChartDTO getRadarChartData(String sessionId) {
        try {
            Integer resumeScore = getSessionResumeScore(sessionId);
            Integer interviewScore = getSessionTotalScore(sessionId);
            Integer demeanorScore = getSessionDemeanorScore(sessionId);
            return interviewRadarService.buildRadarChart(resumeScore, interviewScore, demeanorScore);
        } catch (Exception e) {
            log.error("Failed to get radar chart data, sessionId: {}", sessionId, e);
            RadarChartDTO defaultChart = new RadarChartDTO();
            defaultChart.setResumeScore(0);
            defaultChart.setInterviewPerformance(0);
            defaultChart.setDemeanorEvaluation(0);
            defaultChart.setProfessionalSkills(0);
            defaultChart.setPotentialIndex(0);
            return defaultChart;
        }
    }

    @Override
    public void cacheDemeanorScoreDetails(String sessionId, Integer panicLevel, Integer seriousnessLevel, 
                                          Integer emoticonHandling, Integer compositeScore) {
        try {
            String panicKey = "demeanor:panic:" + sessionId;
            String seriousnessKey = "demeanor:seriousness:" + sessionId;
            String emoticonKey = "demeanor:emoticon:" + sessionId;
            String compositeKey = "demeanor:composite:" + sessionId;
            
            stringRedisTemplate.opsForValue().set(panicKey, panicLevel.toString());
            stringRedisTemplate.opsForValue().set(seriousnessKey, seriousnessLevel.toString());
            stringRedisTemplate.opsForValue().set(emoticonKey, emoticonHandling.toString());
            stringRedisTemplate.opsForValue().set(compositeKey, compositeScore.toString());
            
            // 四个维度评分统一设置过期时间。
            stringRedisTemplate.expire(panicKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(seriousnessKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(emoticonKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(compositeKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            
            log.info("Cached demeanor score details, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId) {
        try {
            return interviewScoreService.getSessionDemeanorScoreDetails(sessionId);
        } catch (Exception e) {
            log.error("Failed to get demeanor detail scores, sessionId: {}", sessionId, e);
            DemeanorScoreDTO defaultScore = new DemeanorScoreDTO();
            defaultScore.setPanicLevel(0);
            defaultScore.setSeriousnessLevel(0);
            defaultScore.setEmoticonHandling(0);
            defaultScore.setCompositeScore(0);
            return defaultScore;
        }
    }

    @Override
    public void cacheInterviewDirection(String sessionId, String interviewDirection) {
        try {
            String cacheKey = INTERVIEW_DIRECTION_KEY + sessionId;
            stringRedisTemplate.opsForValue().set(cacheKey, interviewDirection);
            // 设置缓存过期时间。
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, interviewDirection);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public String getSessionInterviewDirection(String sessionId) {
        try {
            String cacheKey = INTERVIEW_DIRECTION_KEY + sessionId;
            String direction = stringRedisTemplate.opsForValue().get(cacheKey);
            log.info("Interview cache service message", sessionId, direction);
            return direction;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return null;
        }
    }
    @Override
    public void initInterviewFlow(String sessionId, Integer totalQuestions) {
        if (StrUtil.isBlank(sessionId) || totalQuestions == null || totalQuestions <= 0) {
            return;
        }
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(FLOW_STATUS_INIT);
        state.setCurrentIndex(0);
        state.setCurrentQuestionNumber("1");
        state.setTotalQuestions(totalQuestions);
        state.setFollowUpCount(0);
        state.setMaxFollowUp(2);
        state.setVersion(1);
        saveFlowState(sessionId, state);
        updateInterviewFlowStatus(sessionId, FLOW_STATUS_ASKING);
    }

    @Override
    public InterviewFlowState getInterviewFlow(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(cacheKey);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            InterviewFlowState state = new InterviewFlowState();
            state.setStatus(normalizeFlowStatus(asString(entries.get("status"), FLOW_STATUS_INIT)));
            state.setCurrentIndex(asInt(entries.get("currentIndex"), 0));
            state.setCurrentQuestionNumber(asString(entries.get("currentQuestionNumber"), null));
            state.setTotalQuestions(asInt(entries.get("totalQuestions"), 0));
            state.setFollowUpCount(asInt(entries.get("followUpCount"), 0));
            state.setMaxFollowUp(asInt(entries.get("maxFollowUp"), 2));
            state.setVersion(asInt(entries.get("version"), 1));
            return state;
        } catch (Exception e) {
            log.error("Failed to get interview flow state, sessionId: {}", sessionId, e);
            return null;
        }
    }

    @Override
    public void updateInterviewFlowStatus(String sessionId, String status) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(status)) {
            return;
        }
        mutateFlowState(sessionId, state -> state.setStatus(status));
    }

    @Override
    public InterviewFlowState incrementFollowUpCount(String sessionId) {
        return mutateFlowState(sessionId, state -> {
            state.setFollowUpCount((state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()) + 1);
            state.setStatus(FLOW_STATUS_FOLLOW_UP);
        });
    }

    @Override
    public InterviewFlowState startFollowUpQuestion(String sessionId, String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        return mutateFlowState(sessionId, state -> {
            state.setFollowUpCount((state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()) + 1);
            state.setStatus(FLOW_STATUS_FOLLOW_UP);
            state.setCurrentQuestionNumber(questionNumber.trim());
        });
    }

    @Override
    public InterviewFlowState advanceToNextQuestion(String sessionId) {
        return mutateFlowState(sessionId, state -> {
            int currentIndex = state.getCurrentIndex() == null ? 0 : state.getCurrentIndex();
            int totalQuestions = state.getTotalQuestions() == null ? 0 : state.getTotalQuestions();
            int nextIndex = currentIndex + 1;
            state.setFollowUpCount(0);

            if (totalQuestions <= 0 || nextIndex >= totalQuestions) {
                state.setStatus(FLOW_STATUS_COMPLETED);
                state.setCurrentIndex(Math.max(currentIndex, 0));
                state.setCurrentQuestionNumber(null);
            } else {
                state.setCurrentIndex(nextIndex);
                state.setStatus(FLOW_STATUS_ASKING);
                state.setCurrentQuestionNumber(String.valueOf(nextIndex + 1));
            }
        });
    }

    @Override
    public InterviewFlowState markInterviewCompleted(String sessionId) {
        return mutateFlowState(sessionId, state -> {
            state.setStatus(FLOW_STATUS_COMPLETED);
            state.setCurrentQuestionNumber(null);
            state.setFollowUpCount(0);
        });
    }

    @Override
    public boolean markAnswerRequestProcessed(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return true;
        }
        try {
            String cacheKey = INTERVIEW_ANSWER_REQUEST_KEY + sessionId;
            Long added = stringRedisTemplate.opsForSet().add(cacheKey, requestId);
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return added != null && added > 0;
        } catch (Exception e) {
            log.error("Failed to record answer request id, sessionId: {}, requestId: {}", sessionId, requestId, e);
            return true;
        }
    }

    @Override
    public void appendInterviewTurn(String sessionId, InterviewTurnLog turnData) {
        appendInterviewTurnInternal(sessionId, turnData);
    }

    @Override
    public boolean appendInterviewTurnIfAbsent(String sessionId, InterviewTurnLog turnData) {
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return false;
        }
        String requestId = turnData.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            return appendInterviewTurnInternal(sessionId, turnData);
        }
        String requestKey = INTERVIEW_TURN_REQUEST_KEY + sessionId;
        try {
            Long added = stringRedisTemplate.opsForSet().add(requestKey, requestId);
            stringRedisTemplate.expire(requestKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            if (added == null || added <= 0) {
                return true;
            }
            if (appendInterviewTurnInternal(sessionId, turnData)) {
                return true;
            }
            stringRedisTemplate.opsForSet().remove(requestKey, requestId);
            return false;
        } catch (Exception e) {
            log.error("Failed to append interview turn idempotently, sessionId: {}", sessionId, e);
            return false;
        }
    }

    @Override
    public List<InterviewTurnLog> getInterviewTurns(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return new ArrayList<>();
        }
        try {
            String cacheKey = INTERVIEW_TURNS_KEY + sessionId;
            List<String> rawTurns = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
            if (rawTurns == null || rawTurns.isEmpty()) {
                return new ArrayList<>();
            }

            List<InterviewTurnLog> turns = new ArrayList<>();
            for (String rawTurn : rawTurns) {
                if (StrUtil.isBlank(rawTurn)) {
                    continue;
                }
                try {
                    InterviewTurnLog parsed = JSON.parseObject(rawTurn, new TypeReference<InterviewTurnLog>() {
                    });
                    if (parsed != null) {
                        turns.add(parsed);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to parse interview turn item, sessionId: {}", sessionId, ex);
                }
            }
            return turns;
        } catch (Exception e) {
            log.error("Failed to get interview turns, sessionId: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    private boolean appendInterviewTurnInternal(String sessionId, InterviewTurnLog turnData) {
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return false;
        }
        try {
            String cacheKey = INTERVIEW_TURNS_KEY + sessionId;
            String payload = JSON.toJSONString(turnData);
            stringRedisTemplate.opsForList().rightPush(cacheKey, payload);

            Long size = stringRedisTemplate.opsForList().size(cacheKey);
            if (size != null && size > MAX_TURN_LOGS) {
                long start = size - MAX_TURN_LOGS;
                stringRedisTemplate.opsForList().trim(cacheKey, start, -1);
            }

            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return true;
        } catch (Exception e) {
            log.error("Failed to append interview turn, sessionId: {}", sessionId, e);
            return false;
        }
    }

    private InterviewFlowState mutateFlowState(String sessionId, Consumer<InterviewFlowState> mutator) {
        if (StrUtil.isBlank(sessionId) || mutator == null) {
            return null;
        }
        for (int retry = 0; retry < FLOW_CAS_MAX_RETRIES; retry++) {
            InterviewFlowState state = getInterviewFlow(sessionId);
            if (state == null) {
                return null;
            }
            Integer expectedVersion = state.getVersion() == null ? 0 : Math.max(state.getVersion(), 0);
            mutator.accept(state);
            state.setVersion(expectedVersion + 1);
            if (saveFlowStateWithCas(sessionId, expectedVersion, state)) {
                return state;
            }
        }
        log.warn("Flow CAS update retries exhausted, sessionId={}", sessionId);
        return getInterviewFlow(sessionId);
    }

    private void saveFlowState(String sessionId, InterviewFlowState state) {
        String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
        Map<String, String> payload = buildFlowPayload(state);
        stringRedisTemplate.opsForHash().putAll(cacheKey, payload);
        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private boolean saveFlowStateWithCas(String sessionId, Integer expectedVersion, InterviewFlowState state) {
        String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
        Map<String, String> payload = buildFlowPayload(state);
        Long result = stringRedisTemplate.execute(
                FLOW_CAS_UPDATE_SCRIPT,
                Collections.singletonList(cacheKey),
                String.valueOf(expectedVersion == null ? 0 : expectedVersion),
                payload.get("status"),
                payload.get("currentIndex"),
                payload.get("currentQuestionNumber"),
                payload.get("totalQuestions"),
                payload.get("followUpCount"),
                payload.get("maxFollowUp"),
                payload.get("version"),
                String.valueOf(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS))
        );
        return result != null && result > 0;
    }

    private Map<String, String> buildFlowPayload(InterviewFlowState state) {
        Map<String, String> payload = new HashMap<>();
        payload.put("status", normalizeFlowStatus(asString(state.getStatus(), FLOW_STATUS_INIT)));
        payload.put("currentIndex", String.valueOf(state.getCurrentIndex() == null ? 0 : state.getCurrentIndex()));
        payload.put("currentQuestionNumber", asString(state.getCurrentQuestionNumber(), ""));
        payload.put("totalQuestions", String.valueOf(state.getTotalQuestions() == null ? 0 : state.getTotalQuestions()));
        payload.put("followUpCount", String.valueOf(state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()));
        payload.put("maxFollowUp", String.valueOf(state.getMaxFollowUp() == null ? 2 : state.getMaxFollowUp()));
        payload.put("version", String.valueOf(state.getVersion() == null ? 1 : state.getVersion()));
        return payload;
    }

    private String normalizeFlowStatus(String status) {
        if (StrUtil.isBlank(status)) {
            return FLOW_STATUS_INIT;
        }
        String normalized = status.trim().toUpperCase();
        if (FLOW_STATUS_INIT.equals(normalized)
                || FLOW_STATUS_ASKING.equals(normalized)
                || FLOW_STATUS_EVALUATING.equals(normalized)
                || FLOW_STATUS_FOLLOW_UP.equals(normalized)
                || FLOW_STATUS_COMPLETED.equals(normalized)) {
            return normalized;
        }
        return FLOW_STATUS_INIT;
    }

    private static DefaultRedisScript<Long> initFlowCasScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(FLOW_CAS_UPDATE_SCRIPT_TEXT);
        script.setResultType(Long.class);
        return script;
    }

    private Integer asInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String asString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString();
        return StrUtil.isBlank(str) ? defaultValue : str;
    }
}
