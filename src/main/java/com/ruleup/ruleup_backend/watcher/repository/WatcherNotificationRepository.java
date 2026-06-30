package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WatcherNotificationRepository extends JpaRepository<WatcherNotification, UUID> {

    /** 멱등 적재 가드: 같은 감시자×챌린지×날짜가 이미 적재됐는지. */
    boolean existsByWatcherIdAndChallengeIdAndTargetDate(UUID watcherId, UUID challengeId, java.time.LocalDate targetDate);

    /**
     * 발송 스윕 클레임: 예정 시각이 지난 PENDING 을 FOR UPDATE SKIP LOCKED 로 선점.
     * 다중 인스턴스에서도 잠긴 행은 건너뛰어 중복 발송 불가(ShedLock 없이 DB 멱등).
     */
    @Query(value = "SELECT * FROM WatcherNotification " +
            "WHERE status = 'PENDING' AND scheduledAt <= :now " +
            "ORDER BY scheduledAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WatcherNotification> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
