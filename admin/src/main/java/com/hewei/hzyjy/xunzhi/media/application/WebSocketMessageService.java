package com.hewei.hzyjy.xunzhi.media.application;

import com.hewei.hzyjy.xunzhi.media.infrastructure.websocket.AudioTranscriptionWebSocketHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Application service for websocket session status and server-side push.
 */
@Slf4j
@Service
public class WebSocketMessageService {

    @Resource(name = "queryExecutor")
    private ExecutorService queryExecutor;

    public boolean sendMessageToUser(String userId, String type, String message, String data) {
        try {
            AudioTranscriptionWebSocketHandler.sendMessageToUser(userId, type, message, data);
            return true;
        } catch (Exception ex) {
            log.error("Failed to send websocket message, userId={}", userId, ex);
            return false;
        }
    }

    public CompletableFuture<Boolean> sendMessageToUserAsync(String userId, String type, String message, String data) {
        return CompletableFuture.supplyAsync(() -> sendMessageToUser(userId, type, message, data), queryExecutor);
    }

    public boolean isUserOnline(String userId) {
        return AudioTranscriptionWebSocketHandler.isUserOnline(userId);
    }

    public boolean sendSystemNotification(String userId, String message) {
        return sendMessageToUser(userId, "system_notification", message, null);
    }

    public boolean sendTranscriptionResult(String userId, String result, boolean isFinal) {
        String type = isFinal ? "final" : "transcription";
        return sendMessageToUser(userId, type, "Transcription result", result);
    }

    public boolean sendErrorMessage(String userId, String errorMessage) {
        return sendMessageToUser(userId, "error", errorMessage, null);
    }

    public boolean sendStatusUpdate(String userId, String status, String details) {
        return sendMessageToUser(userId, "status_update", status, details);
    }

    public void sendMessageToUsers(Set<String> userIds, String type, String message, String data) {
        userIds.forEach(userId -> {
            if (isUserOnline(userId)) {
                sendMessageToUser(userId, type, message, data);
            }
        });
    }

    public CompletableFuture<Void> sendMessageToUsersAsync(Set<String> userIds, String type, String message, String data) {
        return CompletableFuture.runAsync(() -> sendMessageToUsers(userIds, type, message, data), queryExecutor);
    }
}
