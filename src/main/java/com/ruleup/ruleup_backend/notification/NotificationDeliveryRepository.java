package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationDelivery;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    /**
     * 발송 대기 큐 — 아침 요약 배치와 보정 배치의 핵심.
     * {@code sentAt is null} 을 선두로 두는 이유는 <b>미발송 행이 전체의 극소수</b>이기 때문이다.
     */
    @Query("""
            select d from NotificationDelivery d
             where d.sentAt is null
               and d.scheduledAt <= :now
             order by d.scheduledAt asc
            """)
    List<NotificationDelivery> findDue(@Param("now") Instant now, Limit limit);

    List<NotificationDelivery> findByNotificationId(UUID notificationId);
}
