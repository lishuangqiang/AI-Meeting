package com.hewei.hzyjy.xunzhi.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.dao.entity.InterviewQuestion;
import com.hewei.hzyjy.xunzhi.interview.dao.repository.InterviewQuestionRepository;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionServiceImpl implements InterviewQuestionService {

    private final InterviewQuestionRepository interviewQuestionRepository;

    @Override
    public InterviewQuestion saveInterviewQuestion(InterviewQuestion interviewQuestion) {
        InterviewQuestion existing = null;
        if (StrUtil.isNotBlank(interviewQuestion.getSessionId())) {
            existing = getBySessionId(interviewQuestion.getSessionId());
        }
        if (existing != null) {
            interviewQuestion.setId(existing.getId());
            if (interviewQuestion.getCreateTime() == null) {
                interviewQuestion.setCreateTime(existing.getCreateTime());
            }
            if (interviewQuestion.getDelFlag() == null) {
                interviewQuestion.setDelFlag(existing.getDelFlag());
            }
        }
        if (interviewQuestion.getCreateTime() == null) {
            interviewQuestion.setCreateTime(new Date());
        }
        interviewQuestion.setUpdateTime(new Date());
        if (interviewQuestion.getDelFlag() == null) {
            interviewQuestion.setDelFlag(0);
        }
        return interviewQuestionRepository.save(interviewQuestion);
    }

    @Override
    public InterviewQuestion getBySessionId(String sessionId) {
        Optional<InterviewQuestion> optional = interviewQuestionRepository.findBySessionIdAndDelFlag(sessionId, 0);
        return optional.orElse(null);
    }

    @Override
    public List<InterviewQuestion> getByUserName(String userName) {
        return interviewQuestionRepository.findByUserNameAndDelFlagOrderByCreateTimeDesc(userName, 0);
    }

    @Override
    public IPage<InterviewQuestionRespDTO> pageUserInterviewQuestions(String userName, Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        org.springframework.data.domain.Page<InterviewQuestion> questionPage =
                interviewQuestionRepository.findByUserNameAndDelFlagOrderByCreateTimeDesc(userName, 0, pageable);

        Page<InterviewQuestionRespDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(questionPage.getTotalElements());
        resultPage.setRecords(
                questionPage.getContent().stream()
                        .map(this::convertToRespDTO)
                        .collect(Collectors.toList())
        );
        return resultPage;
    }

    @Override
    public IPage<InterviewQuestionRespDTO> pageAllInterviewQuestions(Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        org.springframework.data.domain.Page<InterviewQuestion> questionPage =
                interviewQuestionRepository.findByDelFlagOrderByCreateTimeDesc(0, pageable);

        Page<InterviewQuestionRespDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(questionPage.getTotalElements());
        resultPage.setRecords(
                questionPage.getContent().stream()
                        .map(this::convertToRespDTO)
                        .collect(Collectors.toList())
        );
        return resultPage;
    }

    @Override
    public List<InterviewQuestion> getByInterviewType(String interviewType) {
        return interviewQuestionRepository.findByInterviewTypeAndDelFlagOrderByCreateTimeDesc(interviewType, 0);
    }

    @Override
    public boolean deleteInterviewQuestion(String id) {
        try {
            Optional<InterviewQuestion> optional = interviewQuestionRepository.findById(id);
            if (optional.isPresent()) {
                InterviewQuestion question = optional.get();
                question.setDelFlag(1);
                question.setUpdateTime(new Date());
                interviewQuestionRepository.save(question);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Delete interview question failed, id={}, error={}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public Integer countByUserName(String userName) {
        return interviewQuestionRepository.countByUserNameAndDelFlag(userName, 0);
    }

    @Override
    public InterviewQuestion createFromAIResponse(
            InterviewQuestionReqDTO reqDTO,
            String aiResponseData,
            Integer responseTime,
            Integer tokenCount) {
        try {
            InterviewQuestion question = new InterviewQuestion();
            question.setSessionId(reqDTO.getSessionId());
            question.setUserName(reqDTO.getUserName());
            question.setAgentId(reqDTO.getAgentId());
            question.setResumeFileUrl(reqDTO.getResumeFileUrl());
            question.setResponseTime(responseTime);
            question.setTokenCount(tokenCount);
            question.setRawResponseData(aiResponseData);

            Map<String, Object> payload = parseStructuredPayload(aiResponseData);
            List<String> questions = toStringList(payload.get("questions"));
            List<String> suggestions = toStringList(firstNonNull(payload.get("sugest"), payload.get("suggestions")));
            Integer resumeScore = toInteger(firstNonNull(payload.get("resumeScore"), payload.get("score")));
            String interviewType = resolveInterviewType(payload);

            setQuestions(question, questions);
            setSuggestions(question, suggestions);
            if (resumeScore != null) {
                question.setResumeScore(resumeScore);
            }
            if (StrUtil.isNotBlank(interviewType)) {
                question.setInterviewType(interviewType.trim());
            }
            return saveInterviewQuestion(question);
        } catch (Exception e) {
            log.error("Create interview question from AI response failed, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage(), e);
            InterviewQuestion errorQuestion = new InterviewQuestion();
            errorQuestion.setSessionId(reqDTO.getSessionId());
            errorQuestion.setUserName(reqDTO.getUserName());
            errorQuestion.setAgentId(reqDTO.getAgentId());
            errorQuestion.setResumeFileUrl(reqDTO.getResumeFileUrl());
            errorQuestion.setResponseTime(responseTime);
            errorQuestion.setTokenCount(tokenCount);
            errorQuestion.setRawResponseData(aiResponseData);
            errorQuestion.setErrorMessage(e.getMessage());
            return saveInterviewQuestion(errorQuestion);
        }
    }

    @Override
    public InterviewQuestion upsertStructuredExtraction(
            String sessionId,
            String userName,
            Long agentId,
            String resumeFileUrl,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        InterviewQuestion question = getBySessionId(sessionId);
        if (question == null) {
            question = new InterviewQuestion();
            question.setSessionId(sessionId);
            question.setDelFlag(0);
        }
        if (StrUtil.isNotBlank(userName)) {
            question.setUserName(userName);
        }
        if (agentId != null) {
            question.setAgentId(agentId);
        }
        if (StrUtil.isNotBlank(resumeFileUrl)) {
            question.setResumeFileUrl(resumeFileUrl);
        }
        setQuestions(question, questions);
        setSuggestions(question, suggestions);
        if (resumeScore != null) {
            question.setResumeScore(resumeScore);
        }
        if (StrUtil.isNotBlank(interviewType)) {
            question.setInterviewType(interviewType.trim());
        }
        if ((question.getRawResponseData() == null || question.getRawResponseData().isBlank())
                && resumeContext != null
                && !resumeContext.isEmpty()) {
            question.setRawResponseData(JSON.toJSONString(resumeContext));
        }
        return saveInterviewQuestion(question);
    }

    private Map<String, Object> parseStructuredPayload(String aiResponseData) {
        if (StrUtil.isBlank(aiResponseData)) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = JSON.parse(aiResponseData);
            Map<String, Object> candidate = findStructuredCandidate(parsed);
            if (candidate != null) {
                return candidate;
            }
            if (parsed instanceof JSONObject jsonObject) {
                return jsonObject.toJavaObject(Map.class);
            }
        } catch (Exception ignored) {
            // Fallback to empty map.
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> findStructuredCandidate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject jsonObject) {
            return findStructuredCandidate(jsonObject.toJavaObject(Map.class));
        }
        if (value instanceof JSONArray jsonArray) {
            return findStructuredCandidate(jsonArray.toJavaObject(List.class));
        }
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) rawMap);
            if (containsStructuredKey(map)) {
                return map;
            }
            for (Object nested : map.values()) {
                Map<String, Object> candidate = findStructuredCandidate(nested);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        if (value instanceof List<?> rawList) {
            for (Object nested : rawList) {
                Map<String, Object> candidate = findStructuredCandidate(nested);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text) || (!text.contains("{") && !text.contains("["))) {
                return null;
            }
            try {
                return findStructuredCandidate(JSON.parse(text));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean containsStructuredKey(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return false;
        }
        return map.containsKey("questions")
                || map.containsKey("sugest")
                || map.containsKey("suggestions")
                || map.containsKey("resumeScore")
                || map.containsKey("type")
                || map.containsKey("interviewType")
                || map.containsKey("direction")
                || map.containsKey("interviewDirection");
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim())
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }
        if (value instanceof JSONArray jsonArray) {
            return jsonArray.toJavaList(String.class).stream()
                    .map(item -> item == null ? null : item.trim())
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }
        String raw = String.valueOf(value).trim();
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            try {
                JSONArray jsonArray = JSON.parseArray(raw);
                return jsonArray.toJavaList(String.class).stream()
                        .map(item -> item == null ? null : item.trim())
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toList());
            } catch (Exception ignored) {
                // Fallback to split.
            }
        }
        String[] parts = raw.split("[,;\\uFF0C\\uFF1B\\n]+");
        return java.util.Arrays.stream(parts)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveInterviewType(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object value = firstNonNull(
                payload.get("type"),
                payload.get("interviewType"),
                payload.get("direction"),
                payload.get("interviewDirection")
        );
        if (value == null) {
            return null;
        }
        String interviewType = String.valueOf(value).trim();
        return StrUtil.isBlank(interviewType) ? null : interviewType;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void setQuestions(InterviewQuestion question, List<String> questions) {
        if (question == null || questions == null || questions.isEmpty()) {
            return;
        }
        question.setQuestions(questions);
        Map<String, String> questionsMap = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            questionsMap.put(String.valueOf(i + 1), questions.get(i));
        }
        question.setQuestionsJson(JSON.toJSONString(questionsMap));
    }

    private void setSuggestions(InterviewQuestion question, List<String> suggestions) {
        if (question == null || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        question.setSuggestions(suggestions);
        Map<String, String> suggestionsMap = new LinkedHashMap<>();
        for (int i = 0; i < suggestions.size(); i++) {
            suggestionsMap.put(String.valueOf(i + 1), suggestions.get(i));
        }
        question.setSuggestionsJson(JSON.toJSONString(suggestionsMap));
    }

    private InterviewQuestionRespDTO convertToRespDTO(InterviewQuestion question) {
        InterviewQuestionRespDTO respDTO = new InterviewQuestionRespDTO();
        BeanUtils.copyProperties(question, respDTO);
        if (StrUtil.isBlank(question.getErrorMessage())) {
            respDTO.setIsSuccess(1);
        } else {
            respDTO.setIsSuccess(0);
        }
        return respDTO;
    }
}
