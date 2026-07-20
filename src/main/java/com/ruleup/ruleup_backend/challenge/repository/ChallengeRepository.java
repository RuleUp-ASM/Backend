package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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
     * 삭제되지 않은 챌린지 1건을 비관적 쓰기 잠금으로 로드(가입 정원 경합·삭제 0명 판정 직렬화, §5·§8).
     * 잠금 하에서 참여자 수를 세고 삽입/삭제해 "마지막 1자리 동시 가입"·"0명 판정 vs 신규 가입"을 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Challenge c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Challenge> findByIdForUpdate(@Param("id") UUID id);

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
     * 활성화 배치(§2): 시작일(startDate)이 도달한 UPCOMING·모더레이션 통과(NONE/APPROVED) 챌린지를
     * FOR UPDATE SKIP LOCKED 로 선점. 모더레이션 미통과(PENDING_REVIEW/REJECTED) 챌린지는
     * 가입·노출이 막혀 있으므로 활성화 대상에서 제외한다.
     * 모더레이션 마감 배치와 동일한 DB 멱등 패턴(다중 인스턴스에서도 중복 전환 불가).
     */
    @Query(value = "SELECT * FROM Challenge " +
            "WHERE status = 'UPCOMING' AND moderationStatus IN ('NONE','APPROVED') AND startDate <= :today " +
            "AND deletedAt IS NULL " +
            "ORDER BY startDate LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findUpcomingDueForActivationForUpdate(@Param("today") LocalDate today, @Param("limit") int limit);

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

    // ===== 탐색(search 스펙) =====

    /**
     * 탐색 후보(= 인기 후보 = 목록 후보): 소프트삭제 X · 모더레이션 APPROVED · 종료 전(endDate ≥ today).
     * 인기 배치·failCount 배치가 순회 대상으로 쓴다.
     */
    @Query("SELECT c FROM Challenge c WHERE c.deletedAt IS NULL " +
            "AND c.moderationStatus IN (com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.NONE, com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.APPROVED) " +
            "AND c.endDate >= :today")
    List<Challenge> findExploreCandidates(@Param("today") LocalDate today);

    /**
     * 목록 화면(§3): 공통 제외(삭제·COMPLETED·미승인·종료) 후 필터(AND)를 적용해 후보를 가져온다.
     * 정렬·커서 페이지네이션은 앱단(ChallengeExploreService)에서 역정규화 값으로 처리한다.
     * 필터 파라미터가 null 이면 그 조건은 건너뛴다(전체). applyManner=true 면 "내가 들어갈 수 있는 것"만.
     */
    @Query("SELECT c FROM Challenge c WHERE c.deletedAt IS NULL " +
            "AND c.moderationStatus IN (com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.NONE, com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.APPROVED) " +
            "AND c.status <> com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.COMPLETED " +
            "AND c.endDate >= :today " +
            "AND (:category IS NULL OR c.category = :category) " +
            "AND (:participationType IS NULL OR c.participationType = :participationType) " +
            "AND (:verificationType IS NULL OR c.verificationType = :verificationType) " +
            "AND (:applyManner = false OR c.minMannerTemperature IS NULL OR c.minMannerTemperature <= :myTemp)")
    List<Challenge> findExploreList(@Param("today") LocalDate today,
                                    @Param("category") String category,
                                    @Param("participationType") ParticipationType participationType,
                                    @Param("verificationType") String verificationType,
                                    @Param("applyManner") boolean applyManner,
                                    @Param("myTemp") BigDecimal myTemp);

    /** 템플릿별 사용자 수(§3.2.1): 파생된 모든(삭제 제외) 챌린지의 현재 참여자 수 합. */
    @Query("SELECT c.templateId, SUM(c.participantCount) FROM Challenge c " +
            "WHERE c.deletedAt IS NULL AND c.templateId IS NOT NULL GROUP BY c.templateId")
    List<Object[]> sumParticipantsByTemplate();

    /** 카테고리별 진행 중(종료 전) 챌린지 수(§2.2): 삭제 X · APPROVED · endDate ≥ today. */
    @Query("SELECT c.category, COUNT(c) FROM Challenge c WHERE c.deletedAt IS NULL " +
            "AND c.moderationStatus IN (com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.NONE, com.ruleup.ruleup_backend.challenge.domain.ChallengeModerationStatus.APPROVED) " +
            "AND c.endDate >= :today GROUP BY c.category")
    List<Object[]> countActiveByCategory(@Param("today") LocalDate today);

    /** 완주율 집계 대상: 완료(COMPLETED)·삭제 X·템플릿 기반 챌린지의 (id, templateId). */
    @Query("SELECT c.id, c.templateId FROM Challenge c " +
            "WHERE c.status = com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.COMPLETED " +
            "AND c.deletedAt IS NULL AND c.templateId IS NOT NULL")
    List<Object[]> findCompletedTemplateChallenges();
}