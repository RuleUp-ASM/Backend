package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationMute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationMuteRepository
        extends JpaRepository<NotificationMute, NotificationMute.Key> {

    List<NotificationMute> findByUserIdOrderByMutedAtAsc(UUID userId);

    /** 컨슈머의 묶음 조회용. */
    List<NotificationMute> findByUserIdIn(List<UUID> userIds);
}
