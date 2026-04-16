package com.hewei.hzyjy.xunzhi.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
public class InterviewResponseParser {

    public Map<String, Object> parseEvaluationResult(String aiResponseStr) {
        String contentStr = extractContentFromInterviewResponse(aiResponseStr);
        Map<String, Object> parsedFromContent = tryParseObject(contentStr);
        if (parsedFromContent != null) {
            return parsedFromContent;
        }
        return tryParseObject(aiResponseStr);
    }

    public Map<String, Object> extractStructuredResult(String aiResponseStr, String... targetKeys) {
        Map<String, Object> parsed = parseEvaluationResult(aiResponseStr);
        Map<String, Object> matched = findFirstObjectContainingKeys(parsed, targetKeys);
        if (matched != null) {
            return matched;
        }

        Map<String, Object> root = tryParseObject(aiResponseStr);
        matched = findFirstObjectContainingKeys(root, targetKeys);
        return matched != null ? matched : root;
    }

    public String extractWorkflowErrorMessage(String aiResponseStr) {
        Map<String, Object> root = tryParseObject(aiResponseStr);
        if (root == null || root.isEmpty()) {
            return null;
        }

        Integer responseCode = parseInteger(root.get("code"));
        if (responseCode == null || responseCode == 0) {
            return null;
        }

        String message = asString(root.get("message"));
        if (StrUtil.isBlank(message)) {
            return "workflow response code=" + responseCode;
        }
        return "workflow response code=" + responseCode + ", message=" + message;
    }

    public String extractContentFromResponse(Map<String, Object> responseMap) {
        try {
            if (responseMap == null || !responseMap.containsKey("choices")) {
                return null;
            }

            Object choicesObj = responseMap.get("choices");
            if (!(choicesObj instanceof List)) {
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;
            if (choices.isEmpty()) {
                return null;
            }

            Map<String, Object> firstChoice = choices.get(0);
            if (firstChoice == null) {
                return null;
            }

            if (firstChoice.containsKey("delta")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> delta = (Map<String, Object>) firstChoice.get("delta");
                if (delta != null && delta.containsKey("content")) {
                    return String.valueOf(delta.get("content"));
                }
            }
            if (firstChoice.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                if (message != null && message.containsKey("content")) {
                    return String.valueOf(message.get("content"));
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to extract content: {}", e.getMessage());
            return null;
        }
    }

    public String extractContentFromInterviewResponse(String aiResponse) {
        try {
            JSONObject jsonObject = JSON.parseObject(aiResponse);
            if (jsonObject == null) {
                return aiResponse;
            }

            if (jsonObject.containsKey("choices")) {
                JSONArray choices = jsonObject.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject firstChoice = choices.getJSONObject(0);
                    if (firstChoice != null) {
                        if (firstChoice.containsKey("delta")) {
                            JSONObject delta = firstChoice.getJSONObject("delta");
                            if (delta != null && delta.containsKey("content")) {
                                return delta.getString("content");
                            }
                        }
                        if (firstChoice.containsKey("message")) {
                            JSONObject message = firstChoice.getJSONObject("message");
                            if (message != null && message.containsKey("content")) {
                                return message.getString("content");
                            }
                        }
                    }
                }
            }

            if (jsonObject.containsKey("content")) {
                return jsonObject.getString("content");
            }
            return aiResponse;
        } catch (Exception e) {
            return aiResponse;
        }
    }

    public Integer parseScoreFromResponse(Map<String, Object> responseMap, String scoreKey) {
        if (responseMap == null || !responseMap.containsKey(scoreKey)) {
            return null;
        }

        Object scoreObj = responseMap.get(scoreKey);
        Integer score;
        if (scoreObj instanceof Number) {
            score = (int) Math.round(((Number) scoreObj).doubleValue());
        } else if (scoreObj instanceof String) {
            try {
                score = (int) Math.round(Double.parseDouble(((String) scoreObj).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }

        if (score < 0) {
            return 0;
        }
        if (score > 100) {
            return 100;
        }
        return score;
    }

    public boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    public String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    public List<String> asStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        if (value instanceof List) {
            List<?> rawList = (List<?>) value;
            return rawList.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }

        String item = value.toString().trim();
        if (StrUtil.isBlank(item)) {
            return Collections.emptyList();
        }

        if (item.startsWith("[") && item.endsWith("]")) {
            try {
                JSONArray jsonArray = JSON.parseArray(item);
                if (jsonArray != null) {
                    return jsonArray.stream()
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .map(String::trim)
                            .filter(StrUtil::isNotBlank)
                            .collect(Collectors.toList());
                }
            } catch (Exception ignored) {
                // Fall through.
            }
        }

        if (item.contains(",") || item.contains(";") || item.contains("\uFF0C") || item.contains("\uFF1B") || item.contains("\n")) {
            String[] parts = item.split("[,;\\uFF0C\\uFF1B\\n]+");
            return java.util.Arrays.stream(parts)
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }

        return Collections.singletonList(item);
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> tryParseObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }

        String normalized = stripMarkdownCodeFence(text);
        JSONObject parsed = tryParseJsonObject(normalized);
        if (parsed == null) {
            String jsonBody = extractFirstJsonObject(normalized);
            parsed = tryParseJsonObject(jsonBody);
        }
        if (parsed == null) {
            return null;
        }

        Map<String, Object> map = parsed.toJavaObject(Map.class);
        Map<String, Object> unwrapped = unwrapJsonFieldMap(map);
        return unwrapped == null ? map : unwrapped;
    }

    private JSONObject tryParseJsonObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripMarkdownCodeFence(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private String extractFirstJsonObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private Map<String, Object> unwrapJsonFieldMap(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }

        Object jsonField = parsed.get("json");
        if (jsonField instanceof JSONObject) {
            return ((JSONObject) jsonField).toJavaObject(Map.class);
        }
        if (jsonField instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapField = (Map<String, Object>) jsonField;
            return new LinkedHashMap<>(mapField);
        }
        if (!(jsonField instanceof String) || StrUtil.isBlank((String) jsonField)) {
            return null;
        }

        try {
            JSONObject inner = JSON.parseObject((String) jsonField);
            return inner == null ? null : inner.toJavaObject(Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> findFirstObjectContainingKeys(Object candidate, String... targetKeys) {
        if (candidate == null) {
            return null;
        }

        if (candidate instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) rawMap);
            if (containsAnyKey(map, targetKeys)) {
                return map;
            }
            for (Object value : map.values()) {
                Map<String, Object> nested = findFirstObjectContainingKeys(value, targetKeys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (candidate instanceof List<?> rawList) {
            for (Object item : rawList) {
                Map<String, Object> nested = findFirstObjectContainingKeys(item, targetKeys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (candidate instanceof JSONObject jsonObject) {
            return findFirstObjectContainingKeys(jsonObject.toJavaObject(Map.class), targetKeys);
        }

        if (candidate instanceof JSONArray jsonArray) {
            return findFirstObjectContainingKeys(jsonArray.toJavaObject(List.class), targetKeys);
        }

        if (candidate instanceof String text) {
            Map<String, Object> nested = tryParseObject(text);
            return nested == null ? null : findFirstObjectContainingKeys(nested, targetKeys);
        }

        return null;
    }

    private boolean containsAnyKey(Map<String, Object> map, String... targetKeys) {
        if (map == null || map.isEmpty() || targetKeys == null) {
            return false;
        }
        for (String key : targetKeys) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
