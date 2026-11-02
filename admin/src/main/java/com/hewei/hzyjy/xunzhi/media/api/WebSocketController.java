package com.hewei.hzyjy.xunzhi.media.api;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hewei.hzyjy.xunzhi.media.application.WebSocketMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for websocket status checks and server-side push.
 */
@RestController
@SaCheckRole("admin")
@RequestMapping("/api/xunzhi/v1/websocket")
@RequiredArgsConstructor
public class WebSocketController {

    private final WebSocketMessageService webSocketMessageService;

    @GetMapping("/user/{userId}/status")
    public ResponseEntity<Map<String, Object>> checkUserStatus(@PathVariable String userId) {
        boolean isOnline = webSocketMessageService.isUserOnline(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("isOnline", isOnline);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/send-message")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestParam String userId,
            @RequestParam String type,
            @RequestParam String message,
            @RequestParam(required = false) String data) {
        boolean success = webSocketMessageService.sendMessageToUser(userId, type, message, data);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "Message delivered" : "Message delivery failed");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/notification/{userId}")
    public ResponseEntity<Map<String, Object>> sendNotification(
            @PathVariable String userId,
            @RequestParam String message) {
        boolean success = webSocketMessageService.sendSystemNotification(userId, message);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "Notification delivered" : "Notification delivery failed");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/transcription/{userId}")
    public ResponseEntity<Map<String, Object>> sendTranscriptionResult(
            @PathVariable String userId,
            @RequestParam String result,
            @RequestParam(defaultValue = "false") boolean isFinal) {
        boolean success = webSocketMessageService.sendTranscriptionResult(userId, result, isFinal);
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Transcription delivered" : "Transcription delivery failed");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/error/{userId}")
    public ResponseEntity<Map<String, Object>> sendErrorMessage(
            @PathVariable String userId,
            @RequestParam String errorMessage) {
        boolean success = webSocketMessageService.sendErrorMessage(userId, errorMessage);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "Error message delivered" : "Error message delivery failed");
        return ResponseEntity.ok(result);
    }
}
