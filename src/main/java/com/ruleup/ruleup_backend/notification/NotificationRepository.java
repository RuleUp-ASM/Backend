package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    /** 내부/레거시 테스트 호환. 신규 API는 소프트 삭제 제외 메서드를 사용한다. */
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<Notification> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID userId);
    long countByUserIdAndReadAtIsNullAndDeletedAtIsNull(UUID userId);
    Optional<Notification> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}
