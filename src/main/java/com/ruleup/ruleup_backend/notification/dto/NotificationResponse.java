package com.ruleup.ruleup_backend.notification.dto;

import java.util.List;

public record NotificationResponse(List<Item> items, long unreadCount,
                                   int retentionDays, String nextCursor) {
    public record Item(String id, String notificationClass, String type, String title,
                       String body, String deeplink, boolean read, String createdAt) {}
    public record ReadResponse(boolean read, long unreadCount) {}
    public record ReadAllResponse(long readCount, long unreadCount) {}
    public record DeleteResponse(boolean removed) {}
}
