package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.verification.domain.Frequency;
import com.ruleup.ruleup_backend.common.verification.PeriodUnit;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 멤버 진행률 비정규화 초기 셋업(§4.2). 멤버가 활성화되면(또는 최초 sync 시) 챌린지 config로 1회 계산.
 *  - FIXED_DAYS: targetDays = 판정 구간(시작일 또는 진행 중 입장이면 가입 다음 날 ~ 종료일) 내 대상 요일 수.
 *  - FREQUENCY : 주기 경계(시작일 기준 롤링) + 필요 완료 횟수(온전한 주기=N, 잘린 주기=ceil(N×겹친 날/주기)).
 */
@Component
public class VerificationMemberSetup {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public void apply(ChallengeMember member, Challenge challenge, VerificationConfig config) {
        if (config != null && config.isFrequency() && config.frequency() != null) {
            applyFrequency(member, challenge, config.frequency());
        } else {
            applyFixedDays(member, challenge);
        }
    }

    /**
     * 이 멤버의 판정 시작일. 진행 중에 들어온 멤버는 가입 다음 날부터 판정되므로 그 전 날짜를 대상일에
     * 넣으면 성공률의 분모가 부풀어 통계가 실제보다 낮게 나온다(QA JOIN-14).
     */
    private static LocalDate judgeFrom(ChallengeMember member, Challenge challenge) {
        if (member.participationStart() == null) return challenge.getStartDate();
        return ChallengeCycle.judgeFrom(challenge.getStartDate(), LocalDate.ofInstant(member.participationStart(), KST));
    }

    private void applyFixedDays(ChallengeMember member, Challenge challenge) {
        List<String> repeat = challenge.getRepeatDays();
        int target = 0;
        for (LocalDate d = judgeFrom(member, challenge); !d.isAfter(challenge.getEndDate() == null ? challenge.getStartDate().plusDays(6) : challenge.getEndDate()); d = d.plusDays(1)) {
            if (repeat != null && repeat.contains(WeekdayCodes.code(d.getDayOfWeek()))) target++;
        }
        member.setupFixedDays(Math.max(target, 1));   // 최소 1 (재셋업 루프 방지)
    }

    private void applyFrequency(ChallengeMember member, Challenge challenge, Frequency f) {
        int n = f.count();
        int periodDays = (f.unit() == PeriodUnit.WEEK) ? 7 : 30;
        LocalDate start = challenge.getStartDate();
        LocalDate end = challenge.getEndDate() == null ? start.plusDays(periodDays - 1L) : challenge.getEndDate();
        // 주기마다 판정 구간과 겹치는 날만큼 필요 횟수를 준다 — 온전한 주기는 N, 잘린 주기(진행 중 입장의
        // 첫 주기·마지막 부분 주기)는 ceil(N×겹친 날/주기).
        LocalDate from = judgeFrom(member, challenge);
        int targetCompletions = 0;
        for (LocalDate p = start; !p.isAfter(end); p = p.plusDays(periodDays)) {
            LocalDate pEnd = p.plusDays(periodDays - 1L).isAfter(end) ? end : p.plusDays(periodDays - 1L);
            LocalDate s = p.isBefore(from) ? from : p;
            if (s.isAfter(pEnd)) continue;
            long days = ChronoUnit.DAYS.between(s, pEnd) + 1;
            targetCompletions += (days == periodDays) ? n : (int) Math.ceil((double) n * days / periodDays);
        }

        LocalDate curEnd = start.plusDays(periodDays - 1L);
        if (curEnd.isAfter(end)) curEnd = end;

        member.setupFrequency(f.unit(), n, start, curEnd, Math.max(targetCompletions, 1));
    }
}
