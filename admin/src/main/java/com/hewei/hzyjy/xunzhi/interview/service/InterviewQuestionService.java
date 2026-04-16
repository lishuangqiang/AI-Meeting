package com.hewei.hzyjy.xunzhi.interview.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;

import java.util.List;
import java.util.Map;

/**
 * 面试题服务接口
 */
public interface InterviewQuestionService {

    /**
     * 保存面试题数据
     */
    InterviewQuestion saveInterviewQuestion(InterviewQuestion interviewQuestion);

    /**
     * 根据会话ID查询面试题
     */
    InterviewQuestion getBySessionId(String sessionId);

    /**
     * 根据用户名查询面试题列表
     */
    List<InterviewQuestion> getByUserName(String userName);

    /**
     * 分页查询用户的面试题
     */
    IPage<InterviewQuestionRespDTO> pageUserInterviewQuestions(String userName, Integer current, Integer size);

    /**
     * 分页查询所有面试题
     */
    IPage<InterviewQuestionRespDTO> pageAllInterviewQuestions(Integer current, Integer size);

    /**
     * 根据面试类型查询面试题
     */
    List<InterviewQuestion> getByInterviewType(String interviewType);

    /**
     * 删除面试题（逻辑删除）
     */
    boolean deleteInterviewQuestion(String id);

    /**
     * 统计用户面试题数量
     */
    Integer countByUserName(String userName);

    /**
     * 根据AI响应数据创建并保存面试题
     */
    InterviewQuestion createFromAIResponse(InterviewQuestionReqDTO reqDTO, String aiResponseData, 
                                           Integer responseTime, Integer tokenCount);

    /**
     * Upsert parsed structured extraction fields for reliable DB fallback.
     */
    InterviewQuestion upsertStructuredExtraction(
            String sessionId,
            String userName,
            Long agentId,
            String resumeFileUrl,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext);
}
