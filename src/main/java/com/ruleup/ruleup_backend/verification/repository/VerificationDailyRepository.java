package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** verification_daily 접근. 멤버×날짜 1줄 + 확정 배치 폴링. */
public interface VerificationDailyRepository extends JpaRepository<VerificationDaily, UUID> {

    /** 그 멤버의 그 날 인증 행(없으면 생성). */
    Optional<VerificationDaily> findByChallengeMemberIdAndTargetDate(UUID challengeMemberId, LocalDate targetDate);

    /** 진행률 재계산용 상태별 카운트. */
    long countByChallengeMemberIdAndStatus(UUID challengeMemberId, VerificationStatus status);

    /** 상세 화면 최근 로그(§3.3 dailyLogs). */
    List<VerificationDaily> findByChallengeMemberIdOrderByTargetDateDesc(UUID challengeMemberId);

    /** 확정 배치: 유예까지 끝나 이제 잠가도 되는 PENDING 행(§2.14). */
    List<VerificationDaily> findByStatusAndFinalizeAfterLessThanEqual(VerificationStatus status, Instant now);

    /**
     * 확정 배치 클레임(§2.14): 유예 끝난 PENDING 행을 FOR UPDATE SKIP LOCKED 로 선점.
     * 동시에 도는 스케줄러는 잠긴 행을 건너뛰어 중복 확정이 구조적으로 불가능(ShedLock 없이 멱등).
     */
    @Query(value = "SELECT * FROM VerificationDaily " +
            "WHERE status = 'PENDING' AND finalizeAfter IS NOT NULL AND finalizeAfter <= :now " +
            "ORDER BY finalizeAfter LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<VerificationDaily> findDuePendingForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * 예비 폴백 잠정성공(§9.2) 중 이의 윈도우가 끝난 행 — 침묵=동의 확정 대상.
     * status=SUCCESS이지만 verifiedVia=MANUAL_FALLBACK & verifiedAt 미세팅 & disputeClosesAt<=now.
     */
    @Query(value = "SELECT * FROM VerificationDaily " +
            "WHERE verifiedVia = 'MANUAL_FALLBACK' AND verifiedAt IS NULL " +
            "AND disputeClosesAt IS NOT NULL AND disputeClosesAt <= :now " +
            "ORDER BY disputeClosesAt LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<VerificationDaily> findFallbackDisputeDue(@Param("now") Instant now, @Param("limit") int limit);
}
