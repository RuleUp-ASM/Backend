package com.ruleup.ruleup_backend.push.repository;

import com.ruleup.ruleup_backend.push.domain.PushOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PushOutboxRepository extends JpaRepository<PushOutbox, UUID> {

    /** 멱등 적재 가드: 같은 유저×챌린지×날짜×타입이 이미 적재됐는지(하루 1건). */
    boolean existsByUserIdAndChallengeIdAndTargetDateAndType(UUID userId, UUID challengeId,
                                                             LocalDate targetDate, String type);

    /**
     * 발송 스윕 클레임: 예정 시각이 지난 PENDING 을 FOR UPDATE SKIP LOCKED 로 선점.
     * 다중 인스턴스에서도 잠긴 행은 건너뛰어 중복 발송 불가(watcher 통지 스윕과 동일 DB 멱등).
     */
    @Query(value = "SELECT * FROM PushOutbox " +
            "WHERE status = 'PENDING' AND scheduledAt <= :now " +
            "ORDER BY scheduledAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<PushOutbox> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
