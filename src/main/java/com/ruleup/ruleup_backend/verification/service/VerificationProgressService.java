package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 멤버 진행률 비정규화 재계산 (§2.9, §4.2). sync·확정배치 공용.
 *  - successDays = SUCCESS day 수.
 *  - failDays: FIXED_DAYS = FAILED day 수(재계산) / FREQUENCY = 주기 롤오버가 관리(보존).
 *  - progressRate = success / targetDays * 100 (상한 100).
 */
@Service
@RequiredArgsConstructor
public class VerificationProgressService {

    private final VerificationDailyRepository dailyRepo;

    /** sync 직후 — 오늘 상태 캐시·마지막 sync 시각도 갱신. */
    public void updateAfterSync(ChallengeMember member, VerificationStatus todayStatus, Instant now) {
        int success = countSuccess(member);
        int fail = failDays(member, success);
        member.applyProgress(success, fail, rate(success, member.getTargetDays()), todayStatus, now);
    }

    /** 확정 배치 직후(과거 날 확정) — todayStatus·lastSyncedAt 은 건드리지 않음. */
    public void recount(ChallengeMember member) {
        int success = countSuccess(member);
        int fail = failDays(member, success);
        member.applyCounts(success, fail, rate(success, member.getTargetDays()));
    }

    /**
     * 확정 배치가 "오늘" 날짜를 처리했을 때 — 진행률 재계산 + todayStatus 캐시 갱신(뱃지용).
     * lastSyncedAt 은 기존값 유지(배치가 sync 시각을 바꾸지 않도록).
     */
    public void recountAndSetToday(ChallengeMember member, VerificationStatus todayStatus) {
        int success = countSuccess(member);
        int fail = failDays(member, success);
        member.applyProgress(success, fail, rate(success, member.getTargetDays()),
                todayStatus, member.getLastSyncedAt());
    }

    private int countSuccess(ChallengeMember m) {
        return (int) dailyRepo.countByChallengeMemberIdAndStatus(m.getId(), VerificationStatus.SUCCESS);
    }

    private int failDays(ChallengeMember m, int success) {
        if (m.getScheduleType() == ScheduleType.FREQUENCY) {
            return m.getFailDays();   // 빈도형: 주기 롤오버가 누적 관리 → 보존
        }
        return (int) dailyRepo.countByChallengeMemberIdAndStatus(m.getId(), VerificationStatus.FAILED);
    }

    private BigDecimal rate(int success, int targetDays) {
        int target = Math.max(targetDays, 1);
        BigDecimal r = BigDecimal.valueOf(success)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(target), 2, RoundingMode.HALF_UP);
        return (r.compareTo(BigDecimal.valueOf(100)) > 0) ? BigDecimal.valueOf(100) : r;
    }
}
