package com.hewei.hzyjy.xunzhi.interview.application;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.xunzhi.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewConversationPageReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewConversationRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewRecordService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewResumePreviewService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewSessionService;
import com.hewei.hzyjy.xunzhi.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionFacade {

    private final InterviewWorkflowService interviewWorkflowService;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewRecordService interviewRecordService;
    private final InterviewResumePreviewService interviewResumePreviewService;
    private final InterviewSessionService interviewSessionService;

    public InterviewSessionCreateRespDTO createSession(Long userId) {
        return interviewSessionService.createSession(userId);
    }

    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        return interviewSessionService.pageConversations(userId, requestParam);
    }

    public List<AgentMessageHistoryRespDTO> getConversationHistory(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        throw new ClientException("interview history is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    public IPage<AgentMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            Long userId) {
        if (StrUtil.isNotBlank(sessionId)) {
            interviewSessionService.requireOwnedSession(sessionId, userId);
        }
        throw new ClientException("interview history paging is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    public void finishSession(String sessionId, Long userId) {
        interviewRecordService.saveInterviewRecordFromRedis(sessionId, userId);
    }

    public void endConversation(String sessionId, Long userId) {
        finishSession(sessionId, userId);
    }

    public InterviewQuestionRespDTO extractInterviewQuestions(
            String sessionId,
            MultipartFile resumePdf,
            Long userId,
            String username) {
        interviewSessionService.markResumeUploading(sessionId, userId);

        InterviewQuestionReqDTO reqDTO = new InterviewQuestionReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setResumePdf(resumePdf);

        InterviewQuestionRespDTO response = interviewWorkflowService.extractInterviewQuestions(reqDTO);
        if (response != null && Integer.valueOf(1).equals(response.getIsSuccess())) {
            interviewSessionService.markReady(
                    sessionId,
                    userId,
                    response.getResumeFileUrl(),
                    response.getInterviewType()
            );
            return response;
        }

        interviewSessionService.markDraft(sessionId, userId);
        return response;
    }

    public InterviewAnswerRespDTO answerInterviewQuestion(
            String sessionId,
            InterviewAnswerReqDTO requestParam,
            Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        requestParam.setSessionId(sessionId);
        return interviewWorkflowService.answerInterviewQuestion(sessionId, requestParam);
    }

    public InterviewAnswerRespDTO getNextQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        return interviewWorkflowService.getNextQuestion(sessionId);
    }

    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        InterviewAnswerRespDTO response = interviewWorkflowService.getCurrentQuestion(sessionId);
        if (response != null && Boolean.TRUE.equals(response.getIsSuccess()) && !Boolean.TRUE.equals(response.getFinished())) {
            interviewSessionService.markInProgressIfReady(sessionId, userId);
        }
        return response;
    }

    public InterviewResumePreviewService.ResumePreviewResource loadResumePreview(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewResumePreviewService.loadResumePreview(sessionId);
    }

    public InterviewSessionRestoreRespDTO restoreInterviewSession(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);

        InterviewSessionRestoreRespDTO response = new InterviewSessionRestoreRespDTO();
        response.setSessionId(sessionId);
        response.setStatus(session.getStatus());
        response.setCanResume(isSessionResumable(session));
        response.setResumeFileUrl(session.getResumeFileUrl());
        response.setInterviewType(session.getInterviewType());

        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        if (question != null) {
            if (StrUtil.isBlank(response.getResumeFileUrl())) {
                response.setResumeFileUrl(question.getResumeFileUrl());
            }
            if (StrUtil.isBlank(response.getInterviewType())) {
                response.setInterviewType(question.getInterviewType());
            }
            response.setResumeScore(question.getResumeScore());
        }

        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId);
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        response.setSuggestions(suggestions);

        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId);
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        if (resumeScore != null) {
            response.setResumeScore(resumeScore);
        }

        if (StrUtil.isBlank(response.getInterviewType()) && question != null) {
            response.setInterviewType(question.getInterviewType());
        }
        return response;
    }

    public Map<String, String> getSessionInterviewQuestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);

        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        return questions;
    }

    public Integer getSessionTotalScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewQuestionCacheService.getSessionTotalScore(sessionId);
    }

    public Map<String, String> getSessionInterviewSuggestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);

        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId);
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        return suggestions;
    }

    public Integer getSessionResumeScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);

        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId);
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        return resumeScore;
    }

    public RadarChartDTO getRadarChartData(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewQuestionCacheService.getRadarChartData(sessionId);
    }

    public String evaluateDemeanor(
            String sessionId,
            MultipartFile userPhoto,
            String requestSessionId,
            Long userId,
            String username) {
        ensureInterviewCanProceed(sessionId, userId);
        if (requestSessionId != null && !sessionId.equals(requestSessionId)) {
            throw new ClientException("sessionId mismatch between path and request parameter");
        }

        DemeanorEvaluationReqDTO reqDTO = new DemeanorEvaluationReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setUserPhoto(userPhoto);
        return interviewWorkflowService.evaluateDemeanor(reqDTO);
    }

    private void ensureInterviewCanProceed(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        if (session == null || !isSessionResumable(session)) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
    }

    private boolean isSessionResumable(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        try {
            return InterviewSessionStatus.valueOf(session.getStatus()).canResume();
        } catch (Exception ex) {
            return false;
        }
    }
}
