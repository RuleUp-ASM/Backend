package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** challenges 접근. 소프트 삭제 행은 제외해서 조회. */
public interface ChallengeRepository extends JpaRepository<Challenge, UUID> {

    /** 삭제되지 않은 챌린지 1건 */
    Optional<Challenge> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 모더레이션 마감 배치(§5.1/§8): REJECTED 상태로 1시간 수정창(fixDeadline)이 지난 챌린지를
     * FOR UPDATE SKIP LOCKED 로 선점. 다중 인스턴스에서도 잠긴 행은 건너뛰어 중복 처리 불가
     * (ShedLock 없이 DB 멱등 — 기존 확정 배치와 동일 패턴).
     */
    @Query(value = "SELECT * FROM Challenge " +
            "WHERE moderationStatus = 'REJECTED' AND fixDeadline IS NOT NULL AND fixDeadline <= :now " +
            "AND deletedAt IS NULL " +
            "ORDER BY fixDeadline LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findRejectedFixWindowExpiredForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * participant_count 원자적 +1 (동시 참여 시 read-modify-write 유실 방지).
     * 멤버 상태 전이가 실제로 일어났을 때만 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Challenge c SET c.participantCount = c.participantCount + 1 WHERE c.id = :id")
    void incrementParticipantCount(@Param("id") UUID id);

    /** participant_count 원자적 -1 (0 미만으로는 내려가지 않음). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Challenge c SET c.participantCount = c.participantCount - 1 WHERE c.id = :id AND c.participantCount > 0")
    void decrementParticipantCount(@Param("id") UUID id);
}