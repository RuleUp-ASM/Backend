package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
