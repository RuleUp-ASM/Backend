package com.ruleup.ruleup_backend.notification;
import com.ruleup.ruleup_backend.notification.domain.*;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 검수 서비스 등 내부에서 알림 발송. */
    @Transactional
    public void notify(UUID userId, NotificationType type, String title, String message) {
        notificationRepository.save(Notification.create(userId, type, title, message));
    }

    @Transactional(readOnly = true)
    public NotificationResponse list(UUID userId) {
        var items = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long unread = notificationRepository.countByUserIdAndReadAtIsNull(userId);
        return NotificationResponse.of(items, unread);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        n.markRead();
    }
}
