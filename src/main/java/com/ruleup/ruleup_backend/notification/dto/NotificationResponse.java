package com.ruleup.ruleup_backend.notification.dto;

import com.ruleup.ruleup_backend.notification.Notification;

import java.util.List;

/** GET /api/v1/notifications 응답. */
public record NotificationResponse(
        long unreadCount,
        List<Item> notifications) {

    public record Item(
            String id,
            String type,
            String title,
            String message,
            boolean read,
            String createdAt) {

        static Item from(Notification n) {
            return new Item(
                    n.getId().toString(),
                    n.getType().name(),
                    n.getTitle(),
                    n.getMessage(),
                    n.isRead(),
                    n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        }
    }

    public static NotificationResponse of(List<Notification> notifications, long unreadCount) {
        return new NotificationResponse(
                unreadCount,
                notifications.stream().map(Item::from).toList());
    }
}
