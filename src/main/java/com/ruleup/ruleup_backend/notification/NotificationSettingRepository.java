package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationSettingRepository
        extends JpaRepository<NotificationSetting, NotificationSetting.Key> {

    List<NotificationSetting> findByUserId(UUID userId);
}
