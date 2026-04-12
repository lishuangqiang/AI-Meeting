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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 闂傚牄鍨奸惁顖涳紣濡偐澶勯悗娑櫳戝﹢鍥礉閳ュ磭鏉介柣婊勫鐞?
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
     * 闂傚牄鍨奸惁顖涳紣濡偐澶勯悗娑櫭晶鐘电磽閳?
     */
    private static final String INTERVIEW_QUESTIONS_KEY = "interview:questions:session:";
    
    /**
     * 闂傚牄鍨奸惁顖氼嚈妤︽鍞寸紓鍌涙尭閻°劑宕滃鍥╃；
     */
    private static final String INTERVIEW_SUGGESTIONS_KEY = "interview:suggestions:session:";
    
    /**
     * 缂佺姭鍋撻柛妯烘閻﹀酣宕氶崱娆戝閻庢稒锚婢х姷绱撻埀?
     */
    private static final String RESUME_SCORE_KEY = "interview:resume_score:session:";

    /**
     * 缂佺姭鍋撻柛妯烘缁劑寮搁崟顐㈩嚙濞戞挸锕ｇ粭鍛村棘閸モ晝澶勯悗娑櫭晶鐘电磽閳ь剟濡?
     */
    private static final String RESUME_CONTEXT_KEY = "interview:resume_context:session:";
    
    /**
     * 缂佷胶鍋為埀顑胯兌椤撴悂鎮堕崱姘辨闁告帒妫涚槐锔锯偓娑櫭晶鐘电磽閳?
     */
    private static final String DEMEANOR_SCORE_KEY = "interview:demeanor_score:session:";
    
    /**
     * 闂傚牄鍨奸惁顖炲棘閻熺増鍊荤紓鍌涙尭閻°劑宕滃鍥╃；
     */
    private static final String INTERVIEW_DIRECTION_KEY = "interview:direction:session:";

    /**
     * Interview flow state key prefix.
     */
    private static final String INTERVIEW_FLOW_KEY = "interview:flow:session:";

    /**
     * Interview answer request-id key prefix for idempotency.
     */
    private static final String INTERVIEW_ANSWER_REQUEST_KEY = "interview:answer:req:session:";

    /**
     * Interview turns key prefix.
     */
    private static final String INTERVIEW_TURNS_KEY = "interview:turns:session:";

    private static final int MAX_TURN_LOGS = 200;

    private static final String FLOW_STATUS_INIT = "INIT";
    private static final String FLOW_STATUS_ASKING = "ASKING";
    private static final String FLOW_STATUS_FOLLOW_UP = "FOLLOW_UP";
    private static final String FLOW_STATUS_COMPLETED = "COMPLETED";
    
    /**
     * 缂傚倹鎸搁悺銊︽交閸ャ劍鍩傞柡鍐ㄧ埣濡潡鏁嶉崼婵堟瘓闁哄啳顔愮槐?
     */
    private static final long CACHE_EXPIRE_HOURS = 24;
    
    @Override
    public void cacheInterviewQuestions(String sessionId, List<String> questions) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            
            // 婵炴挸鎳樺▍搴ㄥ籍瑜忓▓鎴犵磽閹惧磭鎽?
            stringRedisTemplate.delete(cacheKey);
            
            // 閻庢稒锚閸嬪秹寮幍顔界暠闂傚牄鍨奸惁顖涳紣濮楀牏绀夊ù锝堟硶閺併倖锛愬Ο鍝勫▏濞达絾绮堢拹鐒抜eld闁挎稑鐭傞。浠嬫儎椤旇崵绋婂☉鎾剁毉alue
            Map<String, String> questionMap = new HashMap<>();
            for (int i = 0; i < questions.size(); i++) {
                String questionNumber = String.valueOf(i + 1);
                questionMap.put(questionNumber, questions.get(i));
            }
            
            if (!questionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, questionMap);
                // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview questions, sessionId: {}, count: {}", sessionId, questions.size());
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊╂閵忥絿妲稿Λ鐗埫妵鎴犳嫻閵夘垳绀夊ù鍏间亢閻︾祤D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheInterviewSuggestions(String sessionId, List<String> suggestions) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            
            // 婵炴挸鎳樺▍搴ㄥ籍瑜忓▓鎴犵磽閹惧磭鎽?
            stringRedisTemplate.delete(cacheKey);
            
            // 閻庢稒锚閸嬪秹寮幍顔界暠闂傚牄鍨奸惁顖氼嚈妤︽鍞撮柨娑樺婵炲洭鎮介妸銉х处閻犱緡鍠氱槐顏堝矗閾氬倻绋婂☉鎾额嚡ield闁挎稑鑻紓鎾舵媼椤旂厧鏁堕悗褰掆偓娑氱▕濞戞挾鐨璦lue
            Map<String, String> suggestionMap = new HashMap<>();
            for (int i = 0; i < suggestions.size(); i++) {
                String suggestionNumber = String.valueOf(i + 1);
                suggestionMap.put(suggestionNumber, suggestions.get(i));
            }
            
            if (!suggestionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, suggestionMap);
                // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview suggestions, sessionId: {}, count: {}", sessionId, suggestions.size());
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊╂閵忥絿妲哥€点倝缂氶鍛緞鏉堫偉袝闁挎稑濂旂槐鎵嫚濠婄眹: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheResumeScore(String sessionId, Integer resumeScore) {
        try {
            String cacheKey = RESUME_SCORE_KEY + sessionId;
            // 婵炴挸鎳樺▍搴ㄥ籍瑜忓▓鎴犵磽閹惧磭鎽?
            stringRedisTemplate.delete(cacheKey);

            stringRedisTemplate.opsForValue().set(cacheKey, resumeScore.toString());
            // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("闁瑰瓨鍔曟慨娑氱磽閹惧磭鎽犲ù鍏间亢閻?{} 闁汇劌瀚悾婵嬪储閸℃氨妲戦柛? {}", sessionId, resumeScore);
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊х不閳ь剟宕㈤崱姘辨闁告帒妫楅妵鎴犳嫻閵夘垳绀夊ù鍏间亢閻︾祤D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void cacheDemeanorScore(String sessionId, Integer demeanorScore) {
        try {
            String cacheKey = DEMEANOR_SCORE_KEY + sessionId;
            stringRedisTemplate.opsForValue().set(cacheKey, demeanorScore.toString());
            // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("闁瑰瓨鍔曟慨娑氱磽閹惧磭鎽犲ù鍏间亢閻?{} 闁汇劌瀚〃锝夊箑娴ｄ警鍚€闁荤偛妫滈惁搴ㄥ礆? {}", sessionId, demeanorScore);
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊х矈閻愮补鍋撴担渚悁闁荤偛妫滈惁搴ㄥ礆閸℃ぜ浜奸悹鎰╁劵缁辨繃瀵煎宕囨▓ID: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, String> getSessionInterviewQuestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 濞达綀娉曢弫顥瞚nkedHashMap濞ｅ洦绻冪€垫棃骞撻幒鎴濆汲濡炪倕鎼花顓㈡晬鐏炲€熷珯闁圭顦甸。浠嬪矗闁垮绗撻幖?
            Map<String, String> questionMap = new LinkedHashMap<>();
            
            // 閻忓繐妫濋。浠嬪矗閻ゎ垱绁柟璇℃線鐠愮喖寮€涙ɑ娈堕弶鈺傜椤㈡垿骞掗幒鎴犵
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 闁圭粯鍔曡ぐ鍥紣濡搫濞囬弶鈺傜椤㈡垿寮弶璺ㄦ憻闁圭儤甯掔花?
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 濠碘€冲€归悘澶嬶紣濡搫濞囬柡鍕靛灣閸戜粙寮弶璺ㄦ憻闁挎稑鏈€垫粓寮弶璺ㄦ憻闁圭儤甯掔花?
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 闁告熬绠戦崹顖炲箰婢跺﹦鎽熺紒妤嬬細鐟曞棝骞掗幒鎴犵
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 濠碘€冲€归悘澶嬫姜椤掍礁搴婂鎯扮簿鐟欙箓鏁嶇仦鎯х樆閻庢稒顨堥浣圭▔閸欏绗撻幖?
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    questionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview questions from cache, sessionId: {}, count: {}", sessionId, questionMap.size());
            return questionMap;
        } catch (Exception e) {
            log.error("闁兼儳鍢茶ぐ鍥ㄥ濮樺磭妯堥梻鍫涘灱閻︻垱锛愬Ο鎭掍杭閻犳劑鍎荤槐婵囧濮樺磭妯圛D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
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
            log.info("缂傚倹鎸搁悺銊х不閳ь剟宕㈤崱鏇犵憪濞戞挸顑嗛弸鍐箣閹邦剙顫犻柨娑橆啋essionId: {}, keys: {}", sessionId, resumeContext.keySet());
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊х不閳ь剟宕㈤崱鏇犵憪濞戞挸顑嗛弸鍐╁緞鏉堫偉袝闁挎稑顔抏ssionId: {}, error: {}", sessionId, e.getMessage(), e);
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
            log.error("闁兼儳鍢茶ぐ鍥╃不閳ь剟宕㈤崱鏇犵憪濞戞挸顑嗛弸鍐╁緞鏉堫偉袝闁挎稑顔抏ssionId: {}, error: {}", sessionId, e.getMessage(), e);
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
    public Map<String, String> getSessionInterviewSuggestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 濞达綀娉曢弫顥瞚nkedHashMap濞ｅ洦绻冪€垫棃骞撻幒鎴濆汲濡炪倕鎼花顓㈡晬鐏炲€熷珯闁圭顦紓鎾舵媼椤斿墽妞介柛娆撴敱鐢挻鎯?
            Map<String, String> suggestionMap = new LinkedHashMap<>();
            
            // 閻忓繐妫楃紓鎾舵媼椤斿墽妞介柛娆戞焿濞村棝骞戦～顓＄闁轰礁鐡ㄩ弳鐔告交濞戞粠鏀介柟鐑樺笒缁?
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 闁圭粯鍔曡ぐ鍥ь嚈妤︽鍞寸紓鍌涚墪瑜版寧娼诲☉婊庢斀闁轰焦婢橀悺褔骞掗幒鎴犵
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 濠碘€冲€归悘澶岀磽閺嵮冨▏闁哄嫷鍨抽崙浠嬪极閺夎法鎽熼柨娑樻湰鐎垫粓寮弶璺ㄦ憻闁圭儤甯掔花?
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 闁告熬绠戦崹顖炲箰婢跺﹦鎽熺紒妤嬬細鐟曞棝骞掗幒鎴犵
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 濠碘€冲€归悘澶嬫姜椤掍礁搴婂鎯扮簿鐟欙箓鏁嶇仦鎯х樆閻庢稒顨堥浣圭▔閸欏绗撻幖?
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    suggestionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview suggestions from cache, sessionId: {}, count: {}", sessionId, suggestionMap.size());
            return suggestionMap;
        } catch (Exception e) {
            log.error("闁兼儳鍢茶ぐ鍥ㄥ濮樺磭妯堥梻鍫涘灱閻︻垰顕欐ウ娆惧敶濠㈡儼绮剧憴锕傛晬鐏炶偐绐楅悹鍥ㄦ崲D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    @Override
    public String getQuestionByNumber(String sessionId, String questionNumber) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Object question = stringRedisTemplate.opsForHash().get(cacheKey, questionNumber);
            return question != null ? question.toString() : null;
        } catch (Exception e) {
            log.error("闁兼儳鍢茶ぐ鍥紣濡吋绐楀鎯扮簿鐟欙箓鏁嶇仦鑲╃獥閻犲洦鎹: {}, 濡増锚瑜? {}, 闂佹寧鐟ㄩ? {}", sessionId, questionNumber, e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public void clearSessionQuestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);
            log.info("Cleared interview question cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("婵炴挸鎳樺▍搴ㄦ閵忥絿妲稿Λ鐗堫焽缁憋妇鈧稒锚閵囨垹鎷归妷顖滅濞村吋淇洪惁绲€D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public void clearSessionSuggestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);
            log.info("Cleared interview suggestion cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("婵炴挸鎳樺▍搴ㄦ閵忥絿妲哥€点倝缂氶鍛磽閹惧磭鎽犲鎯扮簿鐟欙箓鏁嶇仦鑲╃獥閻犲洦鎹: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洭妫冮姀锝囨Ц濡増锚閸╁瞼绱撻幘宕囨憼
     * 濞村吋锚閸樻稒鎷呯捄銊︽殢JSON闁哄秶鍘х槐锟犲极閻楀牆绁﹂柨娑樿嫰椤┭囧几濠娾偓缁楀鈧稒锚濠€顏堝礆濞嗗骸鈻忛柣鈶╁晸ist闁哄秶鍘х槐锟犲极閻楀牆绁?
     */
    public void loadInterviewQuestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview question data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 濞村吋锚閸樻稒鎷呯捄銊︽殢JSON闁哄秶鍘х槐锟犲极閻楀牆绁?
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
                    
                    log.info("濞寸姴瀛╅弳鐔煎箲椤旇偐姘↗SON闁哄秶鍘х槐锟犲礉閻樼儤绁伴梻鍫涘灱閻︻垱锛愬Ο鍝勭厒缂傚倹鎸搁悺銊╁箣閹邦剙顫犻柨娑樺缁辨壆鎷犲绫? {}, 濡増顭囧ú浼村极娴兼潙娅? {}", sessionId, questionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("閻熸瑱绲鹃悗浠嬫閵忥絿妲稿Λ鐗堛偗SON闁轰胶澧楀畵浣瑰緞鏉堫偉袝闁挎稑鑻惃鍓ф嫚閺囨艾鈻忛柣鈶╁晸ist闁哄秶鍘х槐锟犲极閻楀牆绁﹂柨娑樼焸閺佸﹦鎷? {}", e.getMessage());
                }
            }
            
            // 濠碘€冲€归悘濉塖ON闁哄秶鍘х槐锟犲极閻楀牆绁﹀☉鎾崇Т閻°劑宕烽妸锕€鐏楅悷娆欑稻閻庤姤寰勬潏顐バ曢柨娑樺婵炲洭鎮介埆妾宻t闁哄秶鍘х槐锟犲极閻楀牆绁?
            if (question.getQuestions() != null && !question.getQuestions().isEmpty()) {
                cacheInterviewQuestions(sessionId, question.getQuestions());
                log.info("濞寸姴瀛╅弳鐔煎箲椤旇偐姘↙ist闁哄秶鍘х槐锟犲礉閻樼儤绁伴梻鍫涘灱閻︻垱锛愬Ο鍝勭厒缂傚倹鎸搁悺銊╁箣閹邦剙顫犻柨娑樺缁辨壆鎷犲绫? {}, 濡増顭囧ú浼村极娴兼潙娅? {}", sessionId, question.getQuestions().size());
            }
            
        } catch (Exception e) {
            log.error("濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洭妫冮姀锝囨Ц濡増锚閸╁瞼绱撻幘宕囨憼濠㈡儼绮剧憴锕傛晬鐏炶偐绐楅悹鍥ㄦ崲D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洭妫冮姀锝囨Ц鐎点倝缂氶鍛村礆閹殿喚澶勯悗?
     * 濞村吋锚閸樻稒鎷呯捄銊︽殢JSON闁哄秶鍘х槐锟犲极閻楀牆绁﹂柨娑樿嫰椤┭囧几濠娾偓缁楀鈧稒锚濠€顏堝礆濞嗗骸鈻忛柣鈶╁晸ist闁哄秶鍘х槐锟犲极閻楀牆绁?
     */
    public void loadInterviewSuggestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview suggestion data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 濞村吋锚閸樻稒鎷呯捄銊︽殢JSON闁哄秶鍘х槐锟犲极閻楀牆绁?
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
                    
                    log.info("濞寸姴瀛╅弳鐔煎箲椤旇偐姘↗SON闁哄秶鍘х槐锟犲礉閻樼儤绁伴梻鍫涘灱閻︻垰顕欐ウ娆惧敶闁告帗澹嗙槐锔锯偓娑櫳戦崹姘跺礉閻曞倻绀夊ù鍏间亢閻︾祤D: {}, 鐎点倝缂氶鍛村极娴兼潙娅? {}", sessionId, suggestionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("閻熸瑱绲鹃悗浠嬫閵忥絿妲哥€点倝缂氶鍖ON闁轰胶澧楀畵浣瑰緞鏉堫偉袝闁挎稑鑻惃鍓ф嫚閺囨艾鈻忛柣鈶╁晸ist闁哄秶鍘х槐锟犲极閻楀牆绁﹂柨娑樼焸閺佸﹦鎷? {}", e.getMessage());
                }
            }
            
            // 濠碘€冲€归悘濉塖ON闁哄秶鍘х槐锟犲极閻楀牆绁﹀☉鎾崇Т閻°劑宕烽妸锕€鐏楅悷娆欑稻閻庤姤寰勬潏顐バ曢柨娑樺婵炲洭鎮介埆妾宻t闁哄秶鍘х槐锟犲极閻楀牆绁?
            if (question.getSuggestions() != null && !question.getSuggestions().isEmpty()) {
                cacheInterviewSuggestions(sessionId, question.getSuggestions());
                log.info("濞寸姴瀛╅弳鐔煎箲椤旇偐姘↙ist闁哄秶鍘х槐锟犲礉閻樼儤绁伴梻鍫涘灱閻︻垰顕欐ウ娆惧敶闁告帗澹嗙槐锔锯偓娑櫳戦崹姘跺礉閻曞倻绀夊ù鍏间亢閻︾祤D: {}, 鐎点倝缂氶鍛村极娴兼潙娅? {}", sessionId, question.getSuggestions().size());
            }
            
        } catch (Exception e) {
            log.error("濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洭妫冮姀锝囨Ц鐎点倝缂氶鍛村礆閹殿喚澶勯悗娑櫭妵鎴犳嫻閵夘垳绀夊ù鍏间亢閻︾祤D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洨绮婚埀顒勫储閸℃氨妲戦柛鎺戞閸╁瞼绱撻幘宕囨憼
     */
    public void loadResumeScoreFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null || question.getResumeScore() == null) {
                log.warn("Resume score data not found, sessionId: {}", sessionId);
                return;
            }
            
            cacheResumeScore(sessionId, question.getResumeScore());
            log.info("濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洨绮婚埀顒勫储閸℃氨妲戦柛鎺戞閸╁瞼绱撻幘宕囨憼闁瑰瓨鍔曟慨娑㈡晬鐏炶偐绐楅悹鍥ㄦ崲D: {}, 閻犲洤瀚崹? {}", sessionId, question.getResumeScore());
            
        } catch (Exception e) {
            log.error("濞寸姴瀛╅弳鐔煎箲椤旇偐姘ㄩ柛鏃傚Ь濞村洨绮婚埀顒勫储閸℃氨妲戦柛鎺戞閸╁瞼绱撻幘宕囨憼濠㈡儼绮剧憴锕傛晬鐏炶偐绐楅悹鍥ㄦ崲D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public Integer getSessionTotalScore(String sessionId) {
        try {
            return interviewScoreService.getSessionTotalScore(sessionId, getInterviewTurns(sessionId));
        } catch (Exception e) {
            log.error("闁兼儳鍢茶ぐ鍥ㄥ濮樺磭妯堥柟顒冾嚙閸ㄥ孩寰勬潏顐バ曢柨娑樺缁辨壆鎷犲绫? {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Integer addSessionScore(String sessionId, Integer score) {
        try {
            Integer total = interviewScoreService.addSessionScore(sessionId, score);
            log.info("濞村吋淇洪惁?{} 闁哄牜鍓氶鐓庮嚗濡も偓閸? {}, 缂侀硸鍨甸鎼佸箑鐠囨彃鐎? {}", sessionId, score, total);
            return total;
        } catch (Exception e) {
            log.error("缂侀硸鍨版慨鐐村濮樺磭妯堥柛鎺戞閺嗙喐寰勬潏顐バ曢柨娑樺缁辨壆鎷犲绫? {}, 闁告帒妫欓弳? {}, 闂佹寧鐟ㄩ? {}", sessionId, score, e.getMessage(), e);
            return getSessionTotalScore(sessionId);
        }
    }

    @Override
    public void resetSessionScore(String sessionId) {
        try {
            interviewScoreService.resetSessionScore(sessionId);
            log.info("Reset session score, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("闂佹彃绉堕悿鍡樺濮樺磭妯堥柛鎺戞閺嗙喐寰勬潏顐バ曢柨娑樺缁辨壆鎷犲绫? {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
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
            
            // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
            stringRedisTemplate.expire(panicKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(seriousnessKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(emoticonKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(compositeKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            
            log.info("Cached demeanor score details, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊х矈閻愮补鍋撴担鐣屾闁告帒妫滈娑氱磼閸℃ɑ娈堕柟璇″枛閵囨垹鎷归妷顖滅濞村吋淇洪惁绲€D: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
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
            // 閻犱礁澧介悿鍡樻交閸ャ劍鍩傞柡鍐ㄧ埣濡?
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("闁瑰瓨鍔曟慨娑氱磽閹惧磭鎽犲ù鍏间亢閻?{} 闁汇劌瀚板鎵嫚閺囩喐鐓欓柛? {}", sessionId, interviewDirection);
        } catch (Exception e) {
            log.error("缂傚倹鎸搁悺銊╂閵忥絿妲搁柡鍌滄嚀閹粍寰勬潏顐バ曢柨娑樺缁辨壆鎷犲绫? {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    public String getSessionInterviewDirection(String sessionId) {
        try {
            String cacheKey = INTERVIEW_DIRECTION_KEY + sessionId;
            String direction = stringRedisTemplate.opsForValue().get(cacheKey);
            log.info("闁兼儳鍢茶ぐ鍥ㄥ濮樺磭妯?{} 闁汇劌瀚板鎵嫚閺囩喐鐓欓柛? {}", sessionId, direction);
            return direction;
        } catch (Exception e) {
            log.error("闁兼儳鍢茶ぐ鍥ㄥ濮樺磭妯堥梻鍫涘灱閻︻垶寮悷鐗堝€诲鎯扮簿鐟欙箓鏁嶇仦鑲╃獥閻犲洦鎹: {}, 闂佹寧鐟ㄩ? {}", sessionId, e.getMessage(), e);
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
            state.setStatus(asString(entries.get("status"), FLOW_STATUS_INIT));
            state.setCurrentIndex(asInt(entries.get("currentIndex"), 0));
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
        InterviewFlowState state = getInterviewFlow(sessionId);
        if (state == null) {
            return;
        }
        state.setStatus(status);
        state.setVersion((state.getVersion() == null ? 0 : state.getVersion()) + 1);
        saveFlowState(sessionId, state);
    }

    @Override
    public InterviewFlowState incrementFollowUpCount(String sessionId) {
        InterviewFlowState state = getInterviewFlow(sessionId);
        if (state == null) {
            return null;
        }
        state.setFollowUpCount((state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()) + 1);
        state.setStatus(FLOW_STATUS_FOLLOW_UP);
        state.setVersion((state.getVersion() == null ? 0 : state.getVersion()) + 1);
        saveFlowState(sessionId, state);
        return state;
    }

    @Override
    public InterviewFlowState advanceToNextQuestion(String sessionId) {
        InterviewFlowState state = getInterviewFlow(sessionId);
        if (state == null) {
            return null;
        }

        int currentIndex = state.getCurrentIndex() == null ? 0 : state.getCurrentIndex();
        int totalQuestions = state.getTotalQuestions() == null ? 0 : state.getTotalQuestions();
        int nextIndex = currentIndex + 1;
        state.setFollowUpCount(0);

        if (totalQuestions <= 0 || nextIndex >= totalQuestions) {
            state.setStatus(FLOW_STATUS_COMPLETED);
            state.setCurrentIndex(Math.max(currentIndex, 0));
        } else {
            state.setCurrentIndex(nextIndex);
            state.setStatus(FLOW_STATUS_ASKING);
        }

        state.setVersion((state.getVersion() == null ? 0 : state.getVersion()) + 1);
        saveFlowState(sessionId, state);
        return state;
    }

    @Override
    public InterviewFlowState markInterviewCompleted(String sessionId) {
        InterviewFlowState state = getInterviewFlow(sessionId);
        if (state == null) {
            return null;
        }
        state.setStatus(FLOW_STATUS_COMPLETED);
        state.setVersion((state.getVersion() == null ? 0 : state.getVersion()) + 1);
        saveFlowState(sessionId, state);
        return state;
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
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return;
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
        } catch (Exception e) {
            log.error("Failed to append interview turn, sessionId: {}", sessionId, e);
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

    private void saveFlowState(String sessionId, InterviewFlowState state) {
        String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
        Map<String, String> payload = new HashMap<>();
        payload.put("status", asString(state.getStatus(), FLOW_STATUS_INIT));
        payload.put("currentIndex", String.valueOf(state.getCurrentIndex() == null ? 0 : state.getCurrentIndex()));
        payload.put("totalQuestions", String.valueOf(state.getTotalQuestions() == null ? 0 : state.getTotalQuestions()));
        payload.put("followUpCount", String.valueOf(state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()));
        payload.put("maxFollowUp", String.valueOf(state.getMaxFollowUp() == null ? 2 : state.getMaxFollowUp()));
        payload.put("version", String.valueOf(state.getVersion() == null ? 1 : state.getVersion()));
        stringRedisTemplate.opsForHash().putAll(cacheKey, payload);
        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
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
