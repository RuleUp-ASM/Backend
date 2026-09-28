package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 어떤 날짜가 그 멤버의 인증 대상인지 판단한다.
 * sync 와 확정 배치가 같은 답을 내야 해서 한곳에 둔다 — 어긋나면 배치가 대상 아닌 날을 실패로 확정한다.
 */
public final class VerificationTargetDays {

    public enum Disposition {
        /** 그 날 인증 대상 — 평가·확정 대상이다. */
        EVALUATE,
        /** 요일·기간 밖이라 대상이 아님. */
        NOT_TARGET,
        /** 빈도형에서 이번 주기 몫을 이미 채움. */
        NOT_REQUIRED
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private VerificationTargetDays() {}

    public static Disposition of(VerificationConfig config, Challenge challenge,
                                 ChallengeMember member, LocalDate date) {
        if (challenge == null) return Disposition.NOT_TARGET;
        if (date.isBefore(challenge.getStartDate()) || (challenge.getEndDate() != null && date.isAfter(challenge.getEndDate()))) {
            return Disposition.NOT_TARGET;   // 챌린지 기간 밖
        }
        if (member != null && date.isBefore(judgeFrom(challenge, member))) {
            return Disposition.NOT_TARGET;   // 진행 중 입장 — 가입 당일은 판정하지 않는다(QA JOIN-14)
        }
        if (config.isFrequency()) {
            Integer done = member.getCurPeriodCompleted();
            Integer need = (member.getCurPeriodStart() != null && member.getCurPeriodEnd() != null
                    && !date.isAfter(member.getCurPeriodEnd()))
                    ? Integer.valueOf(periodNeed(challenge, member, member.getCurPeriodStart(), member.getCurPeriodEnd()))
                    : member.getPeriodTarget();
            // 자정~롤오버(00:05) 사이에는 카운터가 아직 지난 주기 것이다. 새 주기 날짜에 그 값을 쓰면
            // 지난주 몫을 채운 사람이 새 주기 첫날 「오늘은 아니다」가 된다(QA VER-13).
            if (member.getCurPeriodEnd() != null && date.isAfter(member.getCurPeriodEnd())) done = 0;
            // 빈도형은 요일 고정이 없어 모든 날이 대상이다. 주기 몫을 이미 채웠으면 더 요구하지 않는다.
            // 과거 날짜에는 현재 카운터를 그대로 보므로 근사값이다 — 주기별 스냅샷은 후속 과제.
            if (done != null && need != null && done >= need) return Disposition.NOT_REQUIRED;
            return Disposition.EVALUATE;
        }
        List<String> repeat = challenge.getRepeatDays();
        boolean target = repeat != null && repeat.contains(WeekdayCodes.code(date.getDayOfWeek()));
        return target ? Disposition.EVALUATE : Disposition.NOT_TARGET;
    }

    /** 이 멤버의 판정 시작일 — 이번 참여 시작 다음 날(진행 중 입장) 또는 챌린지 시작일. */
    public static LocalDate judgeFrom(Challenge challenge, ChallengeMember member) {
        if (member == null || member.participationStart() == null) return challenge.getStartDate();
        return ChallengeCycle.judgeFrom(challenge.getStartDate(), LocalDate.ofInstant(member.participationStart(), KST));
    }

    /**
     * 빈도형 한 주기의 필요 횟수 — 판정 구간과 겹친 날만큼만 요구한다. 온전히 겹치면 N, 잘렸으면
     * ceil(N×겹친 날/주기), 전혀 안 겹치면 0. 진행 중 입장의 첫 주기에 N 을 그대로 요구하면 가입 전
     * 날짜가 미달로 정산돼 성공률·랭킹이 오염된다(리뷰 지적, QA JOIN-14). 성공률 분모(targetDays)도
     * 같은 식으로 센다({@code VerificationMemberSetup}).
     */
    public static int periodNeed(Challenge challenge, ChallengeMember member, LocalDate periodStart, LocalDate periodEnd) {
        int n = member.getPeriodTarget() == null ? 0 : member.getPeriodTarget();
        int periodDays = member.getPeriodUnit() == com.ruleup.ruleup_backend.common.verification.PeriodUnit.WEEK ? 7 : 30;
        return periodNeed(n, periodDays, periodStart, periodEnd, judgeFrom(challenge, member));
    }

    public static int periodNeed(int n, int periodDays, LocalDate periodStart, LocalDate periodEnd, LocalDate judgeFrom) {
        LocalDate from = periodStart.isBefore(judgeFrom) ? judgeFrom : periodStart;
        if (from.isAfter(periodEnd)) return 0;
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, periodEnd) + 1;
        return days >= periodDays ? n : (int) Math.ceil((double) n * days / periodDays);
    }
}
