package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationMuteRepository
        extends JpaRepository<NotificationMute, NotificationMute.Key> {

    List<NotificationMute> findByUserId(UUID userId);
}
