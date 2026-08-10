package com.ruleup.ruleup_backend.challenge.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 사이클 경계 계산 — 사이클은 <b>1주 고정</b>이다(정책 §1, 구 {@code cycleDays} 가변 필드 폐기).
 *
 * <p>사이클 1주차는 챌린지 시작일에 시작한다. 주 중간에 들어온 사람은 그 주를 통째로 평가받으면
 * 불리하므로 <b>다음 사이클 경계부터</b> 판정한다(가입 API {@code countFromCycle}).
 */
public final class ChallengeCycle {

    private ChallengeCycle() {}

    public static final int CYCLE_DAYS = 7;

    /**
     * {@code joinDate} 에 가입한 사람의 판정 시작일.
     *
     * @return 시작 전 가입이면 시작일 그대로, 진행 중 가입이면 다음 사이클 경계
     */
    public static LocalDate countFrom(LocalDate startDate, LocalDate joinDate) {
        if (!joinDate.isAfter(startDate)) return startDate;
        long elapsed = ChronoUnit.DAYS.between(startDate, joinDate);
        long nextBoundary = ((elapsed / CYCLE_DAYS) + 1) * CYCLE_DAYS;
        return startDate.plusDays(nextBoundary);
    }

    /** 사이클 중간 입장이라 판정이 다음 주부터 시작되는가(상세 조회 {@code joinNote}). */
    public static boolean startsNextCycle(LocalDate startDate, LocalDate joinDate) {
        return !countFrom(startDate, joinDate).equals(startDate);
    }
}
