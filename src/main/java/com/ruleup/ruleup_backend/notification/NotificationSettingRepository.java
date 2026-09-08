package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationSettingRepository
        extends JpaRepository<UserNotificationSetting, UUID> {

    /** 컨슈머의 묶음 조회 — 건당 조회를 하면 08:00 8만 건에서 RDS 가 밀린다. */
    List<UserNotificationSetting> findByUserIdIn(List<UUID> userIds);
}
