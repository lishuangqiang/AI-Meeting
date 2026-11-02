package com.hewei.hzyjy.xunzhi.auth.infrastructure.websocket;

import com.hewei.hzyjy.xunzhi.auth.application.CurrentUserService;
import com.hewei.hzyjy.xunzhi.auth.application.WebSocketAuthService;
import jakarta.websocket.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaTokenWebSocketAuthService implements WebSocketAuthService {

    private final CurrentUserService currentUserService;

    @Override
    public boolean isAuthorized(Session session, String pathUserId) {
        String token = resolveToken(session);
        if (token == null || !currentUserService.isValidToken(token)) {
            return false;
        }

        String tokenUsername = currentUserService.getUsernameByToken(token);
        if (tokenUsername == null) {
            return false;
        }
        try {
            Long tokenUserId = currentUserService.getUserIdByUsername(tokenUsername);
            return tokenUserId != null && String.valueOf(tokenUserId).equals(pathUserId);
        } catch (Exception ex) {
            log.warn("Failed to resolve userId from token username, username={}", tokenUsername, ex);
            return false;
        }
    }

    private String resolveToken(Session session) {
        if (session == null) {
            return null;
        }

        Map<String, List<String>> requestParams = session.getRequestParameterMap();
        if (requestParams == null || requestParams.isEmpty()) {
            return null;
        }

        String token = extractFirstParam(requestParams, "token");
        if (token == null) {
            token = extractFirstParam(requestParams, "Authorization");
        }
        if (token == null) {
            token = extractFirstParam(requestParams, "authorization");
        }
        if (token == null) {
            token = extractFirstParam(requestParams, "access_token");
        }
        if (token == null) {
            token = extractFirstParam(requestParams, "satoken");
        }
        if (token == null) {
            return null;
        }
        return normalizeToken(token);
    }

    private String extractFirstParam(Map<String, List<String>> requestParams, String name) {
        List<String> values = requestParams.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String normalizeToken(String token) {
        String trimmed = token.trim();
        if (trimmed.length() >= 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
