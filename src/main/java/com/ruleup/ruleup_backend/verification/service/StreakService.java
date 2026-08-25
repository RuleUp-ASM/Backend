package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.StreakChange;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 연속 기록(스트릭) 계산. 어떤 날짜의 판정 <b>직전</b>과 <b>직후</b> 값을 함께 낸다 —
 * 클라가 "7일째!" 같은 연출을 하려면 두 값이 모두 필요하다.
 *
 * <p>미확정(PENDING)·대상 아님(NOT_TARGET/NOT_REQUIRED) 날짜는 연속을 <b>끊지도 잇지도 않는다</b>.
 * 종결된 판정(SUCCESS/FAILED)만 세고, 최신 날짜부터 거슬러 올라가다 실패를 만나면 멈춘다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StreakService {

    private final VerificationDailyRepository dailyRepo;

    /** targetDate 판정 전/후의 연속 성공 일수. */
    public StreakChange around(UUID challengeMemberId, LocalDate targetDate) {
        List<VerificationDaily> desc = dailyRepo.findByChallengeMemberIdOrderByTargetDateDesc(challengeMemberId);
        return new StreakChange(
                count(desc, d -> d.getTargetDate().isBefore(targetDate)),
                count(desc, d -> !d.getTargetDate().isAfter(targetDate)));
    }

    private int count(List<VerificationDaily> descRows, Predicate<VerificationDaily> inScope) {
        int streak = 0;
        for (VerificationDaily d : descRows) {
            if (!inScope.test(d)) continue;
            VerificationStatus s = d.getStatus();
            if (s == VerificationStatus.SUCCESS) streak++;
            else if (s == VerificationStatus.FAILED) break;
            // PENDING/FAILED_PROVISIONAL/NOT_TARGET/NOT_REQUIRED — 아직 종결 아님, 건너뛴다
        }
        return streak;
    }
}
