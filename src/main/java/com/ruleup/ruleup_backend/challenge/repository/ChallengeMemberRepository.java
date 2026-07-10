package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** challenge_members 접근. */
public interface ChallengeMemberRepository extends JpaRepository<ChallengeMember, UUID> {

    /** 특정 챌린지의 특정 사용자 멤버십 (참여 중복 검사·승인/거절 대상 조회) */
    Optional<ChallengeMember> findByChallengeIdAndUserId(UUID challengeId, UUID userId);

    /** 이미 해당 챌린지에 멤버십이 존재하는지(상태 무관) */
    boolean existsByChallengeIdAndUserId(UUID challengeId, UUID userId);

    /** 상태별 멤버 목록 (3.8 status=ACTIVE/PENDING 필터) */
    List<ChallengeMember> findByChallengeIdAndStatusOrderByJoinedAtAsc(UUID challengeId, MemberStatus status);

    /** 전체 멤버 목록 (3.8 status=ALL, OWNER 전용) */
    List<ChallengeMember> findByChallengeIdOrderByJoinedAtAsc(UUID challengeId);

    /** ACTIVE 멤버 수 (participant_count 정합성 검증·재계산용) */
    long countByChallengeIdAndStatus(UUID challengeId, MemberStatus status);

    /** 내 멤버십 중 상태별 (인증 sync: ACTIVE 챌린지 추림) */
    List<ChallengeMember> findByUserIdAndStatus(UUID userId, MemberStatus status);

    /** 빈도형 주기 롤오버 대상: 현재 주기가 끝난 ACTIVE 멤버. */
    List<ChallengeMember> findByScheduleTypeAndStatusAndCurPeriodEndLessThan(
            ScheduleType scheduleType, MemberStatus status, LocalDate date);

    /** 내 모든 멤버십(상태 무관) — 진행률 status=ALL */
    List<ChallengeMember> findByUserId(UUID userId);

    /** 내 멤버십 중 여러 상태 — 내 챌린지 목록 scope=ALL(ACTIVE+PENDING만, LEFT/REMOVED 제외) */
    List<ChallengeMember> findByUserIdAndStatusIn(UUID userId, Collection<MemberStatus> statuses);

    /**
     * 고스트(무음) 푸시 대상 선점(§고스트 푸시): 셋업 미완료(권한 없음) ACTIVE 멤버를
     * FOR UPDATE SKIP LOCKED 로 집는다. 쿨다운(ghostPushedAt) 지난 것만 — 스팸 방지.
     * AUTO/소프트삭제 판정은 호출부가 챌린지를 로드해 걸러낸다(멤버 행만 잠가 다른 배치와 락 간섭 없음).
     * 다중 인스턴스에서도 잠긴 행은 건너뛰어 중복 발송 없음(활성화/완료 배치와 동일 DB 멱등 패턴).
     */
    @Query(value = "SELECT * FROM ChallengeMember " +
            "WHERE status = 'ACTIVE' AND setupStatus = 'PENDING_SETUP' " +
            "AND (ghostPushedAt IS NULL OR ghostPushedAt <= :cooldownThreshold) " +
            "ORDER BY joinedAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ChallengeMember> findSetupPendingForGhostPushForUpdate(@Param("cooldownThreshold") Instant cooldownThreshold,
                                                                @Param("limit") int limit);

    /**
     * 멤버 상태 원자적 전이(CAS): 현재 status가 {@code from} 중 하나일 때만 {@code to}로 변경.
     * 반환값(영향 행 수)이 1이면 이 호출이 전이를 성사시킨 것 → 참여자 수 증감 등 후속 처리를 1회만 수행.
     * 동시 요청은 행 잠금으로 직렬화되어, 뒤늦은 호출은 0행을 받아 중복 처리를 막는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChallengeMember m SET m.status = :to WHERE m.id = :id AND m.status IN :from")
    int compareAndSetStatus(@Param("id") UUID id,
                            @Param("from") Collection<MemberStatus> from,
                            @Param("to") MemberStatus to);

    // ===== 탐색(search 스펙) 집계 =====

    /** 최근 24h 참여(인기 스코어 §2.1): joinedAt 이 since 이후인 멤버(상태 무관 join 이벤트 근사). */
    List<ChallengeMember> findByJoinedAtAfter(Instant since);

    /**
     * 방별 확정 실패 인원(§3.2.4): 남은 날을 다 성공해도 90% 날 달성이 불가능해진 ACTIVE 멤버 수.
     *  (완주 하한 = ceil(0.9·targetDays) → 허용 실패 상한 = targetDays − 하한, 이를 초과하면 확정 실패)
     */
    @Query("SELECT m.challengeId, " +
            "SUM(CASE WHEN m.targetDays > 0 AND m.failDays > (m.targetDays - CEILING(0.9 * m.targetDays)) THEN 1 ELSE 0 END) " +
            "FROM ChallengeMember m WHERE m.status = :status GROUP BY m.challengeId")
    List<Object[]> aggregateConfirmedFailByChallenge(@Param("status") MemberStatus status);

    /**
     * 완주율(§3.2.3) 집계: 주어진 챌린지들의 (참여자 수, 완주자 수)를 방별로.
     *  완주 = successDays ≥ ceil(0.9·targetDays). ACTIVE·targetDays>0 멤버만 표본.
     */
    @Query("SELECT m.challengeId, COUNT(m), " +
            "SUM(CASE WHEN m.successDays >= CEILING(0.9 * m.targetDays) THEN 1 ELSE 0 END) " +
            "FROM ChallengeMember m WHERE m.challengeId IN :challengeIds " +
            "AND m.status = :status AND m.targetDays > 0 GROUP BY m.challengeId")
    List<Object[]> aggregateCompletionByChallenge(@Param("challengeIds") Collection<UUID> challengeIds,
                                                  @Param("status") MemberStatus status);
}