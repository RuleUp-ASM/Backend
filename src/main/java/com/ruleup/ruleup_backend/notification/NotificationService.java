package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.domain.Notification;
import com.ruleup.ruleup_backend.notification.domain.NotificationClass;
import com.ruleup.ruleup_backend.notification.domain.NotificationSetting;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationSettingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository repository;
    private final NotificationSettingRepository settingRepository;

    @Transactional
    public void notify(UUID userId, NotificationType type, String title, String message) {
        repository.save(Notification.create(userId, type, title, message));
    }

    @Transactional(readOnly = true)
    public NotificationResponse list(UUID userId, String filter, String cursor, Integer requestedSize) {
        NotificationClass selected = filterClass(filter);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 50));
        List<Notification> all = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId)
                .stream().filter(n -> selected == null || n.getNotificationClass() == selected).toList();
        int start = cursorStart(all, cursor);
        List<Notification> page = all.stream().skip(start).limit(size).toList();
        String next = start + page.size() < all.size() && !page.isEmpty()
                ? page.get(page.size() - 1).getId().toString() : null;
        List<NotificationResponse.Item> items = page.stream().map(n -> new NotificationResponse.Item(
                n.getId().toString(), n.getNotificationClass().name(), n.getType().name(), n.getTitle(),
                n.getMessage(), n.getDeeplink(), n.isRead(), n.getCreatedAt().toString())).toList();
        return new NotificationResponse(items, unread(userId), 180, next);
    }

    @Transactional
    public NotificationResponse.ReadResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = repository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead();
        repository.flush();
        return new NotificationResponse.ReadResponse(true, unread(userId));
    }

    @Transactional
    public NotificationResponse.ReadAllResponse markAllRead(UUID userId) {
        List<Notification> notifications = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId);
        long changed = notifications.stream().filter(n -> !n.isRead()).peek(Notification::markRead).count();
        repository.flush();
        return new NotificationResponse.ReadAllResponse(changed, 0);
    }

    @Transactional
    public NotificationResponse.DeleteResponse delete(UUID userId, UUID notificationId) {
        Notification notification = repository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.delete();
        return new NotificationResponse.DeleteResponse(true);
    }

    @Transactional
    public NotificationSettingDtos.Response settings(UUID userId) {
        return response(settingRepository.findById(userId)
                .orElseGet(() -> settingRepository.save(NotificationSetting.defaults(userId))));
    }

    @Transactional
    public NotificationSettingDtos.Response patchSettings(UUID userId, NotificationSettingDtos.PatchRequest request) {
        NotificationSetting setting = settingRepository.findById(userId)
                .orElseGet(() -> settingRepository.save(NotificationSetting.defaults(userId)));
        try {
            setting.patch(request.challengeActivity(), request.roomActivity(), request.tierActivity(),
                    request.marketing(), request.nightPush(), request.mutedChallengeIds());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_SETTING_KEY);
        }
        return response(setting);
    }

    private NotificationSettingDtos.Response response(NotificationSetting setting) {
        return new NotificationSettingDtos.Response(setting.isChallengeActivity(), setting.isRoomActivity(),
                setting.isTierActivity(), setting.isMarketing(), setting.isNightPush(),
                List.copyOf(setting.getMutedChallengeIds()));
    }

    private long unread(UUID userId) {
        return repository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
    }

    private NotificationClass filterClass(String filter) {
        if (filter == null || filter.isBlank() || "ALL".equals(filter)) return null;
        try { return NotificationClass.valueOf(filter); }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE); }
    }

    private int cursorStart(List<Notification> all, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            UUID id = UUID.fromString(cursor);
            for (int i = 0; i < all.size(); i++) if (all.get(i).getId().equals(id)) return i + 1;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException(ErrorCode.CURSOR_INVALID);
    }
}
