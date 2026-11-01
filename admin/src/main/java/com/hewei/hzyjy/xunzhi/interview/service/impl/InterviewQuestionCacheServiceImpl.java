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
import com.hewei.hzyjy.xunzhi.interview.service.cache.InterviewCacheKeys;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitRequest;
import com.hewei.hzyjy.xunzhi.interview.service.model.AnswerCommitResult;
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
     * 追问问题缓存键前缀。
     */
    private static final String INTERVIEW_FOLLOW_UP_QUESTIONS_KEY = "interview:follow_up_questions:session:";

    /**
     * 面试回答请求 ID（幂等）缓存键前缀。
     */
    private static final String INTERVIEW_ANSWER_REQUEST_KEY = "interview:answer:req:session:";

    /**
     * 面试轮次请求去重集合缓存键前缀。
     */
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
     * 答题原子提交 Lua 脚本：
     * 在一个原子边界内完成 ①幂等记录预检/回放 ②入参与键类型/内容预检（全部写入命令之前，杜绝半提交）
     * ③flow 版本 CAS 推进（恒 bump version）④主问题分数聚合（sum/count/avg）
     * ⑤轮次追加（保留上限）⑥幂等提交记录写入（含完整回放字段）⑦全键 TTL 刷新。
     * KEYS 全部使用同一 {sessionId} hash tag（Redis Cluster 多键 Lua 同 slot 要求）。
     * 旧键名（无 hash tag）迁移不允许进入本脚本（会 CROSSSLOT）：由 Java 侧单键脚本先行播种。
     * 返回码：1=本次提交 / 2=已提交回放 / 0=版本冲突 / -1=指纹不符 / -2=预检失败 / -3=记录损坏。
     * 注意：Redis Lua 原子性不等于出错自动回滚，因此所有可能报错的命令（INCRBY/RPUSH/LTRIM/EXPIRE）
     * 的操作对象类型与内容均在写入段之前完成预检；INCRBY 溢出用字符串安全比较预检（ARGV[21]/[22]
     * 为 Java 侧算好的 Long.MAX_VALUE - score / Long.MAX_VALUE - 1，避免 Lua double 精度丢失）。
     */
    private static final String ANSWER_COMMIT_SCRIPT_TEXT =
            "local recType = redis.call('TYPE', KEYS[5]).ok "
                    + "if recType ~= 'hash' and recType ~= 'none' then return {-2} end "
                    + "local rec = redis.call('HGET', KEYS[5], ARGV[2]) "
                    + "if rec ~= false then "
                    + "  local ok, data = pcall(cjson.decode, rec) "
                    + "  if not ok or type(data) ~= 'table' then return {-3} end "
                    + "  if data.questionNumber == ARGV[3] and data.answerDigest == ARGV[4] then "
                    + "    return {2, data.flowVersion, data.currentQuestionNumber, data.status, data.totalScore} "
                    + "  end "
                    + "  return {-1} "
                    + "end "
                    + "local expectedVersion = tonumber(ARGV[1]) "
                    + "local shouldScore = tonumber(ARGV[5]) "
                    + "local score = tonumber(ARGV[6]) "
                    + "local currentTotal = tonumber(ARGV[18]) "
                    + "local ttl = tonumber(ARGV[19]) "
                    + "local okTurn, turn = pcall(cjson.decode, ARGV[17]) "
                    + "if expectedVersion == nil or not string.match(ARGV[1], '^%-?%d+$') or expectedVersion < 0 "
                    + "  or (ARGV[5] ~= '0' and ARGV[5] ~= '1') "
                    + "  or score == nil or not string.match(ARGV[6], '^%-?%d+$') or score < 0 or score > 100 "
                    + "  or currentTotal == nil or not string.match(ARGV[18], '^%-?%d+$') "
                    + "  or currentTotal < 0 or currentTotal > 100 "
                    + "  or ttl == nil or not string.match(ARGV[19], '^%d+$') or ttl <= 0 "
                    + "  or not string.match(ARGV[21], '^%d+$') or not string.match(ARGV[22], '^%d+$') "
                    + "  or not okTurn or type(turn) ~= 'table' then return {-2} end "
                    + "local flowType = redis.call('TYPE', KEYS[1]).ok "
                    + "local sumType = redis.call('TYPE', KEYS[2]).ok "
                    + "local cntType = redis.call('TYPE', KEYS[3]).ok "
                    + "local avgType = redis.call('TYPE', KEYS[4]).ok "
                    + "local turnsType = redis.call('TYPE', KEYS[6]).ok "
                    + "if (flowType ~= 'hash' and flowType ~= 'none') "
                    + "  or (sumType ~= 'string' and sumType ~= 'none') "
                    + "  or (cntType ~= 'string' and cntType ~= 'none') "
                    + "  or (avgType ~= 'string' and avgType ~= 'none') "
                    + "  or (turnsType ~= 'list' and turnsType ~= 'none') then return {-2} end "
                    + "local function padCompare(a, b) "
                    + "  local la, lb = #a, #b "
                    + "  if la > lb then return a, string.rep('0', la - lb) .. b "
                    + "  else return string.rep('0', lb - la) .. a, b end "
                    + "end "
                    + "if sumType == 'string' then "
                    + "  local sumVal = redis.call('GET', KEYS[2]) "
                    + "  if sumVal ~= false then "
                    + "    if not string.match(sumVal, '^%-?%d+$') then return {-2} end "
                    + "    if string.match(sumVal, '^%d+$') then "
                    + "      local a, b = padCompare(sumVal, ARGV[21]) "
                    + "      if a > b then return {-2} end "
                    + "    end "
                    + "  end "
                    + "end "
                    + "if cntType == 'string' then "
                    + "  local cntVal = redis.call('GET', KEYS[3]) "
                    + "  if cntVal ~= false then "
                    + "    if not string.match(cntVal, '^%-?%d+$') then return {-2} end "
                    + "    if string.match(cntVal, '^%d+$') then "
                    + "      local a, b = padCompare(cntVal, ARGV[22]) "
                    + "      if a > b then return {-2} end "
                    + "    end "
                    + "  end "
                    + "end "
                    + "local current = redis.call('HGET', KEYS[1], 'version') "
                    + "if current == false then current = '0' end "
                    + "if tostring(current) ~= tostring(ARGV[1]) then return {0} end "
                    + "local newVersion = expectedVersion + 1 "
                    + "redis.call('HSET', KEYS[1], "
                    + "  'status', ARGV[7], 'currentIndex', ARGV[8], 'currentQuestionNumber', ARGV[9], "
                    + "  'totalQuestions', ARGV[10], 'followUpCount', ARGV[11], 'maxFollowUp', ARGV[12], "
                    + "  'version', tostring(newVersion)) "
                    + "local avg = ARGV[18] "
                    + "if shouldScore == 1 then "
                    + "  local sum = redis.call('INCRBY', KEYS[2], score) "
                    + "  local cnt = redis.call('INCRBY', KEYS[3], 1) "
                    + "  avg = tostring(math.floor((sum / cnt) + 0.5)) "
                    + "  redis.call('SET', KEYS[4], avg) "
                    + "end "
                    + "turn.totalScore = tonumber(avg) "
                    + "local encodedTurn = cjson.encode(turn) "
                    + "redis.call('RPUSH', KEYS[6], encodedTurn) "
                    + "redis.call('LTRIM', KEYS[6], -" + MAX_TURN_LOGS + ", -1) "
                    + "local record = cjson.encode({ "
                    + "  questionNumber = ARGV[3], answerDigest = ARGV[4], flowVersion = newVersion, "
                    + "  status = ARGV[7], currentQuestionNumber = ARGV[9], totalScore = tonumber(avg), "
                    + "  score = score, feedback = ARGV[13], nextQuestionNumber = ARGV[14], nextQuestion = ARGV[15], "
                    + "  finished = (ARGV[16] == 'true' or ARGV[16] == '1'), "
                    + "  isFollowUp = (ARGV[20] == 'true' or ARGV[20] == '1'), followUpCount = ARGV[11], "
                    + "  turnPayload = encodedTurn }) "
                    + "redis.call('HSET', KEYS[5], ARGV[2], record) "
                    + "for i = 1, #KEYS do redis.call('EXPIRE', KEYS[i], ttl) end "
                    + "return {1, tostring(newVersion), ARGV[9], ARGV[7], avg}";

    private static final DefaultRedisScript<List> ANSWER_COMMIT_SCRIPT = initAnswerCommitScript();

    /**
     * 仅当 flow 键不存在时才整体写入（EXISTS 判断与 HSET/EXPIRE 同一原子边界），
     * 消除"先查后写"的 TOCTOU：并发线程已创建/推进 flow 时直接放弃，绝不盲写覆盖。
     * 返回 1=已恢复 / 0=flow 已存在跳过。
     */
    private static final String RESTORE_FLOW_SCRIPT_TEXT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
                    + "redis.call('HSET', KEYS[1], "
                    + "'status', ARGV[1], "
                    + "'currentIndex', ARGV[2], "
                    + "'currentQuestionNumber', ARGV[3], "
                    + "'totalQuestions', ARGV[4], "
                    + "'followUpCount', ARGV[5], "
                    + "'maxFollowUp', ARGV[6], "
                    + "'version', ARGV[7]) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[8])) "
                    + "return 1";

    private static final DefaultRedisScript<Long> RESTORE_FLOW_SCRIPT = initRestoreFlowScript();

    /**
     * 单键条件播种脚本（CROSSSLOT 安全）：新键不存在时写入值并设 TTL，已存在时跳过。
     * 用于旧键名分数聚合的迁移：以"条件 SET"保证与并发提交脚本的 INCRBY 无覆盖竞态
     * （任何一方先落键后，另一方看到 EXISTS 即跳过，聚合基数不会丢失也不会被覆盖）。
     * 返回 1=本次播种 / 0=已存在跳过。
     */
    private static final String SEED_IF_ABSENT_SCRIPT_TEXT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
                    + "redis.call('SET', KEYS[1], ARGV[1]) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "
                    + "return 1";

    private static final DefaultRedisScript<Long> SEED_IF_ABSENT_SCRIPT = initSeedIfAbsentScript();
    
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
            // Cluster 下多键 DEL 要求同 slot，逐个删除。
            stringRedisTemplate.delete(cacheKey);
            stringRedisTemplate.delete(followUpCacheKey);
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
            if (StrUtil.isBlank(direction)) {
                InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
                if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
                    direction = question.getInterviewType().trim();
                    cacheInterviewDirection(sessionId, direction);
                }
            }
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
        // 原子守护：仅在 flow 缺失时初始化（EXISTS 判断与 HSET 同脚本），
        // 杜绝"先查后写"覆盖并发推进（A 发现缺失、B 已初始化并推进后 A 仍写回版本 1）。
        // 仅当本次实际写入成功（键原先不存在）时才继续置为 ASKING。
        if (writeFlowIfAbsent(sessionId, state)) {
            updateInterviewFlowStatus(sessionId, FLOW_STATUS_ASKING);
        }
    }

    @Override
    public InterviewFlowState getInterviewFlow(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            InterviewFlowState state = readFlowState(InterviewCacheKeys.flow(sessionId));
            if (state != null) {
                return state;
            }
            // 旧键名（无 hash tag）兼容迁移：新键缺失时回读旧键，经单键 EXISTS 守护脚本写入。
            InterviewFlowState legacy = readFlowState(InterviewCacheKeys.legacyFlow(sessionId));
            if (legacy != null) {
                restoreInterviewFlow(sessionId, legacy);
                InterviewFlowState migrated = readFlowState(InterviewCacheKeys.flow(sessionId));
                if (migrated != null) {
                    // 仅确认新键已成功写入后才删除旧键；失败/异常时保留旧键，下次访问重试。
                    stringRedisTemplate.delete(InterviewCacheKeys.legacyFlow(sessionId));
                    return migrated;
                }
                return legacy;
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get interview flow state, sessionId: {}", sessionId, e);
            return null;
        }
    }

    private InterviewFlowState readFlowState(String cacheKey) {
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
    public void restoreInterviewFlow(String sessionId, InterviewFlowState flowState) {
        if (StrUtil.isBlank(sessionId) || flowState == null) {
            return;
        }
        try {
            // 原子守护：EXISTS 判断与写入同脚本，仅在 flow 缺失时初始化；
            // 已存在时放弃写入，避免回退并发推进或倒灌旧 version。
            if (!writeFlowIfAbsent(sessionId, flowState)) {
                log.warn("Skip restoreInterviewFlow because flow already exists, sessionId: {}", sessionId);
            }
        } catch (Exception ex) {
            log.error("Failed to restore interview flow, sessionId: {}", sessionId, ex);
        }
    }

    @Override
    public AnswerCommitResult commitAnswerAtomically(AnswerCommitRequest request) {
        if (request == null || StrUtil.isBlank(request.getSessionId())) {
            return AnswerCommitResult.invalid();
        }
        String sessionId = request.getSessionId();
        try {
            // 旧键名（无 hash tag）兼容迁移：先以单键条件 SET 播种新分数聚合键（CROSSSLOT 安全，
            // 且与提交脚本的 INCRBY 无覆盖竞态），再执行同 slot 的多键原子提交。
            // 播种失败必须 fail closed：从 0 起步 INCRBY 会丢失历史聚合基数。
            if (!migrateScoreAggregateIfAbsent(sessionId)) {
                log.error("Failed to migrate legacy score aggregate before atomic commit, sessionId: {}", sessionId);
                return AnswerCommitResult.invalid();
            }
            int safeScore = request.getScore() == null ? 0 : Math.max(0, request.getScore());
            List<?> result = stringRedisTemplate.execute(
                    ANSWER_COMMIT_SCRIPT,
                    List.of(
                            InterviewCacheKeys.flow(sessionId),
                            InterviewCacheKeys.sessionScoreSum(sessionId),
                            InterviewCacheKeys.sessionScoreCount(sessionId),
                            InterviewCacheKeys.sessionScore(sessionId),
                            InterviewCacheKeys.committedAnswer(sessionId),
                            InterviewCacheKeys.turns(sessionId)
                    ),
                    String.valueOf(request.getExpectedVersion() == null ? 0 : request.getExpectedVersion()),
                    request.getRequestId(),
                    request.getQuestionNumber(),
                    request.getAnswerDigest(),
                    request.isShouldScore() ? "1" : "0",
                    String.valueOf(request.getScore() == null ? 0 : request.getScore()),
                    request.getStatus(),
                    String.valueOf(request.getCurrentIndex() == null ? 0 : request.getCurrentIndex()),
                    request.getCurrentQuestionNumber() == null ? "" : request.getCurrentQuestionNumber(),
                    String.valueOf(request.getTotalQuestions() == null ? 0 : request.getTotalQuestions()),
                    String.valueOf(request.getFollowUpCount() == null ? 0 : request.getFollowUpCount()),
                    String.valueOf(request.getMaxFollowUp() == null ? 2 : request.getMaxFollowUp()),
                    request.getFeedback() == null ? "" : request.getFeedback(),
                    request.getNextQuestionNumber() == null ? "" : request.getNextQuestionNumber(),
                    request.getNextQuestion() == null ? "" : request.getNextQuestion(),
                    request.isFinished() ? "true" : "false",
                    request.getTurnPayload(),
                    String.valueOf(request.getCurrentTotalScore() == null ? 0 : request.getCurrentTotalScore()),
                    String.valueOf(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS)),
                    request.isFollowUp() ? "true" : "false",
                    String.valueOf(Long.MAX_VALUE - safeScore),
                    String.valueOf(Long.MAX_VALUE - 1)
            );
            return parseCommitResult(result);
        } catch (Exception ex) {
            // 脚本执行失败：fail closed，不允许任何非原子降级路径。
            log.error("Failed to execute atomic answer commit, sessionId: {}, requestId: {}", sessionId, request.getRequestId(), ex);
            return AnswerCommitResult.invalid();
        }
    }

    private AnswerCommitResult parseCommitResult(List<?> result) {
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return AnswerCommitResult.invalid();
        }
        long code = Long.parseLong(String.valueOf(result.get(0)));
        switch ((int) code) {
            case 1:
            case 2: {
                Long flowVersion = result.size() > 1 && result.get(1) != null
                        ? Long.parseLong(String.valueOf(result.get(1))) : null;
                String currentQuestionNumber = result.size() > 2 && result.get(2) != null
                        ? String.valueOf(result.get(2)) : null;
                String status = result.size() > 3 && result.get(3) != null
                        ? String.valueOf(result.get(3)) : null;
                Integer totalScore = result.size() > 4 && result.get(4) != null
                        ? Integer.parseInt(String.valueOf(result.get(4))) : null;
                return code == 1
                        ? AnswerCommitResult.committed(flowVersion, currentQuestionNumber, status, totalScore)
                        : AnswerCommitResult.replayed(flowVersion, currentQuestionNumber, status, totalScore);
            }
            case 0:
                return AnswerCommitResult.versionConflict();
            case -1:
                return AnswerCommitResult.fingerprintMismatch();
            case -3:
                return AnswerCommitResult.corruptRecord();
            default:
                log.warn("Atomic answer commit returned unexpected code: {}", code);
                return AnswerCommitResult.invalid();
        }
    }

    @Override
    public String findCommittedAnswerRecord(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return null;
        }
        try {
            Object record = stringRedisTemplate.opsForHash().get(
                    InterviewCacheKeys.committedAnswer(sessionId), requestId);
            return record == null ? null : record.toString();
        } catch (Exception e) {
            log.error("Failed to find committed answer record, sessionId: {}, requestId: {}", sessionId, requestId, e);
            return null;
        }
    }

    @Override
    public void clearSessionRuntime(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            // Redis Cluster 下多键 DEL 要求同 slot；新旧键形制（{sessionId} tag 与无 tag）slot 不同，
            // 因此逐个删除，禁止把新旧键放入同一次多键命令。
            List<String> keys = List.of(
                    InterviewCacheKeys.flow(sessionId),
                    InterviewCacheKeys.sessionScoreSum(sessionId),
                    InterviewCacheKeys.sessionScoreCount(sessionId),
                    InterviewCacheKeys.sessionScore(sessionId),
                    InterviewCacheKeys.turns(sessionId),
                    InterviewCacheKeys.committedAnswer(sessionId),
                    InterviewCacheKeys.turnRequest(sessionId),
                    InterviewCacheKeys.answerRequest(sessionId),
                    INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId,
                    InterviewCacheKeys.legacyFlow(sessionId),
                    InterviewCacheKeys.legacyTurns(sessionId),
                    InterviewCacheKeys.legacySessionScoreSum(sessionId),
                    InterviewCacheKeys.legacySessionScoreCount(sessionId),
                    InterviewCacheKeys.legacySessionScore(sessionId)
            );
            for (String key : keys) {
                stringRedisTemplate.delete(key);
            }
            log.info("Cleared session runtime state, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to clear session runtime state, sessionId: {}", sessionId, e);
        }
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
            String cacheKey = InterviewCacheKeys.turns(sessionId);
            List<String> rawTurns = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
            // 旧键名（无 hash tag）兼容迁移：读时合并旧键轮次（旧在前、新在后）。
            // 不做任何写入/删除：多键操作会触发 Cluster CROSSSLOT，且迁移失败会丢历史。
            List<String> legacyRawTurns =
                    stringRedisTemplate.opsForList().range(InterviewCacheKeys.legacyTurns(sessionId), 0, -1);
            if (legacyRawTurns != null && !legacyRawTurns.isEmpty()) {
                List<String> merged = new ArrayList<>(legacyRawTurns);
                if (rawTurns != null) {
                    merged.addAll(rawTurns);
                }
                rawTurns = merged;
            }
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

    /**
     * 旧键名（无 hash tag）分数聚合迁移：把旧 sum/count 播种到新键（条件 SET，幂等）。
     * 旧键不删除（靠 TTL 消亡），失败时返回 false 由调用方 fail closed，绝不丢失历史基数。
     * <p>
     * 【发布前提】本迁移依赖"旧键已停止写入"。滚动发布期间若仍有旧版本实例在写旧 score_sum/count，
     * 其后续增量不会进入新键，播种后总分将不一致。发布策略（必须二选一）：
     * 1) 维护窗口：先停止旧实例流量并排空进行中的会话（24h TTL 内旧键自然消亡），再滚动新版本；
     * 2) 排空会话：禁止新版本接入旧键会话，直至全部存量会话结束或过期。
     * 不得在旧实例仍在处理会话时直接滚动，否则只能保证"播种前"的历史，播种后的旧增量必然丢失。
     */
    private boolean migrateScoreAggregateIfAbsent(String sessionId) {
        try {
            String legacySum = stringRedisTemplate.opsForValue().get(
                    InterviewCacheKeys.legacySessionScoreSum(sessionId));
            if (StrUtil.isNotBlank(legacySum)
                    && !seedKeyIfAbsent(InterviewCacheKeys.sessionScoreSum(sessionId), legacySum)) {
                return false;
            }
            String legacyCount = stringRedisTemplate.opsForValue().get(
                    InterviewCacheKeys.legacySessionScoreCount(sessionId));
            if (StrUtil.isNotBlank(legacyCount)
                    && !seedKeyIfAbsent(InterviewCacheKeys.sessionScoreCount(sessionId), legacyCount)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to migrate legacy score aggregate, sessionId: {}", sessionId, e);
            return false;
        }
    }

    private boolean seedKeyIfAbsent(String key, String value) {
        try {
            Long result = stringRedisTemplate.execute(
                    SEED_IF_ABSENT_SCRIPT,
                    List.of(key),
                    value,
                    String.valueOf(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS))
            );
            return result != null;
        } catch (Exception e) {
            log.error("Failed to seed key if absent, key: {}", key, e);
            return false;
        }
    }

    private boolean appendInterviewTurnInternal(String sessionId, InterviewTurnLog turnData) {
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return false;
        }
        try {
            String cacheKey = InterviewCacheKeys.turns(sessionId);
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

    /**
     * 仅当 flow 键不存在时整体写入（EXISTS 判断与 HSET/EXPIRE 同一原子边界，单键脚本，CROSSSLOT 安全）。
     * 所有"缺失才初始化/恢复"的路径（initInterviewFlow / restoreInterviewFlow / 旧键迁移）统一走这里，
     * 禁止任何先查后写的盲写覆盖。
     *
     * @return true=本次实际写入；false=flow 已存在跳过
     */
    private boolean writeFlowIfAbsent(String sessionId, InterviewFlowState state) {
        Long result = stringRedisTemplate.execute(
                RESTORE_FLOW_SCRIPT,
                List.of(InterviewCacheKeys.flow(sessionId)),
                normalizeFlowStatus(asString(state.getStatus(), FLOW_STATUS_INIT)),
                String.valueOf(state.getCurrentIndex() == null ? 0 : state.getCurrentIndex()),
                asString(state.getCurrentQuestionNumber(), ""),
                String.valueOf(state.getTotalQuestions() == null ? 0 : state.getTotalQuestions()),
                String.valueOf(state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()),
                String.valueOf(state.getMaxFollowUp() == null ? 2 : state.getMaxFollowUp()),
                String.valueOf(state.getVersion() == null ? 1 : state.getVersion()),
                String.valueOf(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS))
        );
        return result != null && result > 0;
    }

    private boolean saveFlowStateWithCas(String sessionId, Integer expectedVersion, InterviewFlowState state) {
        String cacheKey = InterviewCacheKeys.flow(sessionId);
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

    private static DefaultRedisScript<List> initAnswerCommitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(ANSWER_COMMIT_SCRIPT_TEXT);
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<Long> initRestoreFlowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RESTORE_FLOW_SCRIPT_TEXT);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> initSeedIfAbsentScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(SEED_IF_ABSENT_SCRIPT_TEXT);
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
