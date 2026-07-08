package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
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
     * 활성화 배치(§5.7): 시작일(startDate)이 도달한 RECRUITING·APPROVED 챌린지를
     * FOR UPDATE SKIP LOCKED 로 선점. 모더레이션 미승인(PENDING_REVIEW/REJECTED) 챌린지는
     * 가입·노출이 막혀 있으므로 활성화 대상에서 제외한다.
     * 모더레이션 마감 배치와 동일한 DB 멱등 패턴(다중 인스턴스에서도 중복 전환 불가).
     */
    @Query(value = "SELECT * FROM Challenge " +
            "WHERE status = 'RECRUITING' AND moderationStatus = 'APPROVED' AND startDate <= :today " +
            "AND deletedAt IS NULL " +
            "ORDER BY startDate LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findRecruitingDueForActivationForUpdate(@Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * 모더레이션 재검 배치(§5.1/§8): AI 검수가 결론을 못 낸 채(=UNAVAILABLE 또는 리스너 실패로)
     * PENDING_REVIEW 로 지체된 챌린지를 FOR UPDATE SKIP LOCKED 로 선점해 다시 검수한다.
     *  - 생성/이름변경 직후엔 AFTER_COMMIT 리스너가 곧 처리하므로, 마지막 변경(updatedAt)이
     *    threshold 보다 오래된 것만 집어 정상 처리분과 겹치지 않게 한다.
     *  - AI 가 한 번이라도 결론(APPROVED/REJECTED)을 내면 PENDING_REVIEW 를 벗어나 이 대상에서 빠진다
     *    (= "AI 거친 뒤엔 재검 안 함").
     * 활성화/마감 배치와 동일한 DB 멱등 패턴(다중 인스턴스에서도 중복 검수·중복 알림 없음).
     */
    @Query(value = "SELECT * FROM Challenge " +
            "WHERE moderationStatus = 'PENDING_REVIEW' AND deletedAt IS NULL AND updatedAt <= :threshold " +
            "ORDER BY updatedAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findPendingModerationStalledForUpdate(@Param("threshold") Instant threshold, @Param("limit") int limit);

    /**
     * 완료 배치(§5.5/§5.7): 종료일(endDate)이 지난 ACTIVE 챌린지를 FOR UPDATE SKIP LOCKED 로 선점.
     * endDate 는 마지막 활동일(포함)이므로 그 날을 넘긴(endDate < today) 것만 COMPLETED 로 넘긴다.
     * 활성화 배치와 동일한 DB 멱등 패턴.
     */
    @Query(value = "SELECT * FROM Challenge " +
            "WHERE status = 'ACTIVE' AND endDate < :today AND deletedAt IS NULL " +
            "ORDER BY endDate LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findActiveDueForCompletionForUpdate(@Param("today") LocalDate today, @Param("limit") int limit);

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