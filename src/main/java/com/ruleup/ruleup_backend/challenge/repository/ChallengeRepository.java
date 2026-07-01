package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * 챌린지 전체 조회(탐색/목록, §3.x). 노출 조건: 모더레이션 APPROVED · 미삭제.
     * PENDING_REVIEW/REJECTED 는 타인에게 비노출이므로 목록에서 제외(§5.1).
     *  - category : null 이면 전체, 값이 있으면 해당 카테고리만(InterestCategory name).
     *  - status   : null 이면 RECRUITING+ACTIVE 노출, 값이 있으면 해당 status 만.
     * 정렬은 호출 측 Pageable(기본 createdAt desc)로 제어.
     */
    @Query("""
            SELECT c FROM Challenge c
            WHERE c.deletedAt IS NULL
              AND c.moderationStatus = com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.APPROVED
              AND (:category IS NULL OR c.category = :category)
              AND (
                   (:status IS NULL AND c.status IN (
                        com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.RECRUITING,
                        com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.ACTIVE))
                OR (:status IS NOT NULL AND c.status = :status)
              )
            """)
    Page<Challenge> findVisibleForList(@Param("category") String category,
                                       @Param("status") ChallengeStatus status,
                                       Pageable pageable);

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