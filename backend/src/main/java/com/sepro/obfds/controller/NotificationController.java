package com.sepro.obfds.controller;

import com.sepro.obfds.dto.MessageResponse;
import com.sepro.obfds.dto.NotificationResponse;
import com.sepro.obfds.notification.NotificationService;
import com.sepro.obfds.security.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer notifications (FR-16). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;

    public NotificationController(
            NotificationService notificationService, CurrentUserService currentUserService) {
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> myNotifications() {
        Long customerId = currentUserService.requireCustomer().getId();
        return ResponseEntity.ok(notificationService.listForCustomer(customerId));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        Long customerId = currentUserService.requireCustomer().getId();
        return ResponseEntity.ok(Map.of("unread", notificationService.countUnread(customerId)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(@PathVariable Long id) {
        Long customerId = currentUserService.requireCustomer().getId();
        return ResponseEntity.ok(notificationService.markAsRead(id, customerId));
    }

    @PostMapping("/read-all")
    public ResponseEntity<MessageResponse> markAllRead() {
        Long customerId = currentUserService.requireCustomer().getId();
        int updated = notificationService.markAllAsRead(customerId);
        return ResponseEntity.ok(new MessageResponse(updated + " notification(s) marked as read."));
    }
}
