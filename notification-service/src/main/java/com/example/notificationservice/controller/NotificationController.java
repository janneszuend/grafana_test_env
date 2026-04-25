package com.example.notificationservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    @PostMapping
    public ResponseEntity<?> sendNotification(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Simulate-Error", defaultValue = "false") boolean simulateError) {

        String type = (String) request.getOrDefault("type", "ORDER_CONFIRMATION");
        String recipient = (String) request.getOrDefault("recipient", "customer@example.com");
        String orderId = (String) request.getOrDefault("orderId", "unknown");

        if (simulateError) {
            log.error("Simulated notification failure for orderId={}, type={}", orderId, type);
            throw new NotificationException("Simulated notification service failure for orderId=" + orderId);
        }

        String notificationId = UUID.randomUUID().toString();
        log.info("Notification queued: id={}, type={}, recipient={}, orderId={}", notificationId, type, recipient, orderId);

        return ResponseEntity.accepted().body(Map.of(
                "notificationId", notificationId,
                "status", "QUEUED",
                "type", type,
                "recipient", recipient,
                "orderId", orderId
        ));
    }

    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<?> handleNotificationError(NotificationException ex) {
        return ResponseEntity.internalServerError().body(Map.of(
                "error", "NOTIFICATION_FAILED",
                "message", ex.getMessage()
        ));
    }

    static class NotificationException extends RuntimeException {
        NotificationException(String message) {
            super(message);
        }
    }
}
