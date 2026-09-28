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
        if (member != null && member.getJoinedAt() != null && date.isBefore(ChallengeCycle.judgeFrom(
                challenge.getStartDate(), LocalDate.ofInstant(member.getJoinedAt(), KST)))) {
            return Disposition.NOT_TARGET;   // 진행 중 입장 — 가입 당일은 판정하지 않는다(QA JOIN-14)
        }
        if (config.isFrequency()) {
            Integer done = member.getCurPeriodCompleted();
            Integer need = member.getPeriodTarget();
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
}
