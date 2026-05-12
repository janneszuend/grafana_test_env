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

        String type = (String) request.getOrDefault("type", "TRANSACTION_CONFIRMATION");
        String recipient = (String) request.getOrDefault("recipient", "customer@bank.example");
        String transactionId = (String) request.getOrDefault("transactionId",
                request.getOrDefault("orderId", "unknown").toString());

        if (simulateError) {
            log.error("Simulated notification failure for transactionId={}, type={}", transactionId, type);
            throw new NotificationException("Simulated notification service failure for transactionId=" + transactionId);
        }

        String notificationId = UUID.randomUUID().toString();
        log.info("Notification queued: id={}, type={}, recipient={}, transactionId={}", notificationId, type, recipient, transactionId);

        return ResponseEntity.accepted().body(Map.of(
                "notificationId", notificationId,
                "status", "QUEUED",
                "type", type,
                "recipient", recipient,
                "transactionId", transactionId
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
