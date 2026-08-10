package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** verification_daily 접근. 멤버×날짜 1줄 + 확정 배치 폴링. */
public interface VerificationDailyRepository extends JpaRepository<VerificationDaily, UUID> {

    /** 그 멤버의 그 날 인증 행(없으면 생성). */
    Optional<VerificationDaily> findByChallengeMemberIdAndTargetDate(UUID challengeMemberId, LocalDate targetDate);

    /** 진행률 재계산용 상태별 카운트. */
    long countByChallengeMemberIdAndStatus(UUID challengeMemberId, VerificationStatus status);

    /** 챌린지 내 특정 상태(예: SUCCESS) 인증 이력 존재 여부 — 진행 중 삭제 패널티 트리거 판정(§8). */
    boolean existsByChallengeIdAndStatus(UUID challengeId, VerificationStatus status);

    /**
     * 그 멤버의 가장 이른 성공일. 중도 탈퇴 감점의 "1년 이상 성공을 이어왔는가"(정책 §10.1) 판정에 쓴다.
     * 성공 이력이 없으면 null.
     */
    @Query("SELECT MIN(v.targetDate) FROM VerificationDaily v "
            + "WHERE v.challengeMemberId = :memberId AND v.status = :status")
    LocalDate findEarliestDate(@Param("memberId") UUID memberId, @Param("status") VerificationStatus status);

    /** 상세 화면 최근 로그(§3.3 dailyLogs). */
    List<VerificationDaily> findByChallengeMemberIdOrderByTargetDateDesc(UUID challengeMemberId);

    /** 캘린더 당일 보강: 유저의 특정 날짜 인증 행(RoutineOutcome 지연분 보완). */
    List<VerificationDaily> findByUserIdAndTargetDate(UUID userId, LocalDate targetDate);

    List<VerificationDaily> findByChallengeIdAndStatusIn(
            UUID challengeId, Collection<VerificationStatus> statuses);

    /** 처리 대기함(pending-reviews): 챌린지의 승인 대기 폴백 제출. */
    List<VerificationDaily> findByChallengeIdAndFallbackApprovalStatus(
            UUID challengeId, com.ruleup.ruleup_backend.verification.domain.FallbackApprovalStatus status);

    /** 확정 배치: 유예까지 끝나 이제 잠가도 되는 PENDING 행(§2.14). */
    List<VerificationDaily> findByStatusAndFinalizeAfterLessThanEqual(VerificationStatus status, Instant now);

    /**
     * 확정 배치 클레임(§2.14): 유예 끝난 PENDING 행을 FOR UPDATE SKIP LOCKED 로 선점.
     * 동시에 도는 스케줄러는 잠긴 행을 건너뛰어 중복 확정이 구조적으로 불가능(ShedLock 없이 멱등).
     * 승인 대기 중인 폴백 행(fallbackApprovalStatus='PENDING')은 자동 확정에서 제외한다(§10.2).
     * 기각된 폴백('REJECTED')은 자동 경로로 복귀하므로 재판정 대상에 포함한다(§10.2 v3).
     */
    @Query(value = "SELECT * FROM VerificationDaily " +
            "WHERE status = 'PENDING' AND finalizeAfter IS NOT NULL AND finalizeAfter <= :now " +
            "AND (fallbackApprovalStatus IS NULL OR fallbackApprovalStatus = 'REJECTED') " +
            "ORDER BY finalizeAfter LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<VerificationDaily> findDuePendingForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 잠정 실패 확정 배치(§8.7): 이의 제기 창(disputeClosesAt)이 지난 FAILED_PROVISIONAL 행을 선점.
     * 처리(pending) 중인 이의 제기가 있으면 배치가 건너뛰어 확정을 보류한다(호출부에서 재확인).
     */
    @Query(value = "SELECT * FROM VerificationDaily " +
            "WHERE status = 'FAILED_PROVISIONAL' AND disputeClosesAt IS NOT NULL AND disputeClosesAt <= :now " +
            "ORDER BY disputeClosesAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<VerificationDaily> findProvisionalDueForLockForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 추천 아웃컴 수집(RoutineOutcomeCollector): 확정 시각이 워터마크 이후인 종결(SUCCESS/FAILED) 행.
     * verifiedAt 오름차순 → 페이지 상한(Pageable)으로 한 배치 처리량을 제한한다.
     */
    @Query("SELECT d FROM VerificationDaily d " +
            "WHERE d.status IN :statuses AND d.verifiedAt IS NOT NULL AND d.verifiedAt >= :since " +
            "ORDER BY d.verifiedAt ASC")
    List<VerificationDaily> findTerminalSince(@Param("statuses") Collection<VerificationStatus> statuses,
                                              @Param("since") Instant since, Pageable pageable);
}
