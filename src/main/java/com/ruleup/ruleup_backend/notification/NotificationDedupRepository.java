package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.NotificationDedup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface NotificationDedupRepository
        extends JpaRepository<NotificationDedup, NotificationDedup.Key> {

    /**
     * 중복 판정과 갱신 사이에 다른 요청이 끼어들면 같은 알림이 두 번 나간다.
     * 행 잠금으로 읽어 <b>경합에서도 중복이 안 나가게</b> 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationDedup> findWithLockByUserIdAndTypeAndTargetKey(
            java.util.UUID userId, String type, String targetKey);
}
