package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.verification.domain.Frequency;
import com.ruleup.ruleup_backend.common.verification.PeriodUnit;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 멤버 진행률 비정규화 초기 셋업(§4.2). 멤버가 활성화되면(또는 최초 sync 시) 챌린지 config로 1회 계산.
 *  - FIXED_DAYS: targetDays = 기간 내 대상 요일 수.
 *  - FREQUENCY : 주기 경계(시작일 기준 롤링) + 필요 완료 횟수(중간 주기=N, 마지막 부분 주기=ceil(N×남은/주기)).
 */
@Component
public class VerificationMemberSetup {

    public void apply(ChallengeMember member, Challenge challenge, VerificationConfig config) {
        if (config != null && config.isFrequency() && config.frequency() != null) {
            applyFrequency(member, challenge, config.frequency());
        } else {
            applyFixedDays(member, challenge);
        }
    }

    private void applyFixedDays(ChallengeMember member, Challenge challenge) {
        List<String> repeat = challenge.getRepeatDays();
        int target = 0;
        for (LocalDate d = challenge.getStartDate(); !d.isAfter(challenge.getEndDate()); d = d.plusDays(1)) {
            if (repeat != null && repeat.contains(WeekdayCodes.code(d.getDayOfWeek()))) target++;
        }
        member.setupFixedDays(Math.max(target, 1));   // 최소 1 (재셋업 루프 방지)
    }

    private void applyFrequency(ChallengeMember member, Challenge challenge, Frequency f) {
        int n = f.count();
        int periodDays = (f.unit() == PeriodUnit.WEEK) ? 7 : 30;
        LocalDate start = challenge.getStartDate();
        LocalDate end = challenge.getEndDate();
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;

        int fullPeriods = (int) (totalDays / periodDays);
        int remainder = (int) (totalDays % periodDays);
        int lastPartial = (remainder > 0) ? (int) Math.ceil((double) n * remainder / periodDays) : 0;
        int periodsTotal = fullPeriods + (remainder > 0 ? 1 : 0);
        int targetCompletions = n * fullPeriods + lastPartial;

        LocalDate curEnd = start.plusDays(periodDays - 1L);
        if (curEnd.isAfter(end)) curEnd = end;

        member.setupFrequency(f.unit(), n, start, curEnd, Math.max(periodsTotal, 1), Math.max(targetCompletions, 1));
    }
}
