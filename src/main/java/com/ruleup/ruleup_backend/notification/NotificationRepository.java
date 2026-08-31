package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * 알림함 커서 페이징. {@code (created_at, id)} 복합 커서라 같은 밀리초에 여러 건이 들어와도
     * 페이지 경계에서 빠지거나 겹치지 않는다.
     */
    @Query("""
            select n from Notification n
             where n.userId = :userId
               and n.deletedAt is null
               and (:cursorAt is null
                    or n.createdAt < :cursorAt
                    or (n.createdAt = :cursorAt and n.id < :cursorId))
             order by n.createdAt desc, n.id desc
            """)
    List<Notification> findInbox(@Param("userId") UUID userId,
                                 @Param("cursorAt") Instant cursorAt,
                                 @Param("cursorId") UUID cursorId,
                                 Limit limit);

    Optional<Notification> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    long countByUserIdAndReadAtIsNullAndDeletedAtIsNull(UUID userId);

    /** 6개월 경과분 정리 배치. */
    List<Notification> findByCreatedAtBefore(Instant threshold, Limit limit);
}
