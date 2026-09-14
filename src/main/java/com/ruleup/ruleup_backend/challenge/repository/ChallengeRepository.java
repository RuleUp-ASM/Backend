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
     * 무제한 방 가입이 쓰는 <b>공유 잠금</b> 읽기.
     *
     * <p>무제한 방은 셀 정원이 없어 쓰기 잠금을 잡지 않는다 — 잡으면 같은 방 가입이 서로를
     * 기다린다. 그렇다고 잠그지 않으면 <b>정원을 거는 설정 변경과의 경합</b>이 열린다:
     * 설정 트랜잭션이 아직 커밋되기 전이라 가입이 여전히 「무제한」을 보고, 정원 검사 없이
     * 들어간다. 설정 쪽은 그 가입을 못 세고 커밋해 정원을 넘긴다.
     *
     * <p>공유 잠금이 정확히 이 틈을 메운다. 공유 잠금끼리는 <b>서로 막지 않으므로</b> 가입이
     * 가입을 기다리지 않고, 설정 변경의 쓰기 잠금과는 충돌하므로 둘 중 하나가 반드시 뒤에 선다.
     * 또한 잠금 읽기라 읽기 스냅샷을 고정하지 않아, 뒤따르는 정원 검사가 최신 값을 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM Challenge c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Challenge> findByIdForShare(@Param("id") UUID id);

    /**
     * 활성화 배치: 시작일(start_date)이 도달한 UPCOMING 챌린지를 FOR UPDATE SKIP LOCKED 로 선점.
     * 심사 상태와 무관하게 활성화한다(심사 중 기능 제한 없음 — 대체 표시가 노출을 가린다).
     * 다중 인스턴스에서도 중복 전환 불가(DB 멱등 패턴).
     */
    @Query(value = "SELECT * FROM challenges " +
            "WHERE status = 'UPCOMING' AND start_date <= :today " +
            "AND deleted_at IS NULL " +
            "ORDER BY start_date LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findUpcomingDueForActivationForUpdate(@Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * 심사 재시도 배치: 항목별 심사(제목/설명/이미지)가 결론을 못 낸 채(IN_REVIEW) 지체된 챌린지를
     * FOR UPDATE SKIP LOCKED 로 선점해 다시 심사한다. 생성/수정 직후엔 AFTER_COMMIT 리스너가 곧
     * 처리하므로, 마지막 변경(updated_at)이 threshold 보다 오래된 것만 집는다.
     */
    @Query(value = "SELECT * FROM challenges " +
            "WHERE (moderation_title = 'IN_REVIEW' OR moderation_description = 'IN_REVIEW' " +
            "       OR moderation_image = 'IN_REVIEW') " +
            "AND deleted_at IS NULL AND updated_at <= :threshold " +
            "ORDER BY updated_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findModerationInReviewStalledForUpdate(@Param("threshold") Instant threshold, @Param("limit") int limit);

    /**
     * 완료 배치(§5.5/§5.7): 종료일(endDate)이 지난 ACTIVE 챌린지를 FOR UPDATE SKIP LOCKED 로 선점.
     * endDate 는 마지막 활동일(포함)이므로 그 날을 넘긴(endDate < today) 것만 COMPLETED 로 넘긴다.
     * 활성화 배치와 동일한 DB 멱등 패턴.
     */
    @Query(value = "SELECT * FROM challenges " +
            "WHERE status = 'ACTIVE' AND end_date < :today AND deleted_at IS NULL " +
            "ORDER BY end_date LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Challenge> findActiveDueForCompletionForUpdate(@Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * 그 방의 정원만 읽는다 — 값이 없으면 무제한이다.
     *
     * <p>가입 경로가 <b>트랜잭션을 열기 전에</b> 부른다. 정원이 있는 방에서는 잠금 읽기가 트랜잭션의
     * 첫 문장이어야 하는데(읽기 스냅샷이 먼저 고정되면 정원 COUNT 가 락 대기 중 커밋된 가입을
     * 놓친다), 그러려면 잠글지 말지를 트랜잭션 밖에서 알아야 한다.
     *
     * @return 그 방이 없으면 빈 리스트. 있으면 한 줄이며, 그 값이 {@code null} 이면 무제한이다
     *         (정원은 없을 수 있는 값이라 {@code Optional} 로는 「방이 없음」과 구분되지 않는다)
     */
    @Query("SELECT c.maxParticipants FROM Challenge c WHERE c.id = :id")
    List<Integer> findCapacityById(@Param("id") UUID id);

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