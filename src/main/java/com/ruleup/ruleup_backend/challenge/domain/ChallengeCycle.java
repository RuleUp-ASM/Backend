package com.ruleup.ruleup_backend.challenge.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 사이클 경계 계산 — 사이클은 <b>1주 고정</b>이다(정책 §1, 구 {@code cycleDays} 가변 필드 폐기).
 *
 * <p>사이클 1주차는 챌린지 시작일에 시작한다. 진행 중에 들어온 사람은 <b>판정은 가입 다음 날부터</b>
 * ({@link #judgeFrom}, 가입 API {@code countFromCycle}), <b>점수는 다음 사이클 경계부터</b>({@link #countFrom}) 잡는다.
 */
public final class ChallengeCycle {

    private ChallengeCycle() {}

    public static final int CYCLE_DAYS = 7;

    /**
     * {@code joinDate} 에 가입한 사람의 <b>점수</b> 시작일(사이클 점수·연속 실패 강퇴가 이 경계부터 센다).
     *
     * @return 시작 전 가입이면 시작일 그대로, 진행 중 가입이면 다음 사이클 경계
     */
    public static LocalDate countFrom(LocalDate startDate, LocalDate joinDate) {
        if (!joinDate.isAfter(startDate)) return startDate;
        long elapsed = ChronoUnit.DAYS.between(startDate, joinDate);
        long nextBoundary = ((elapsed + CYCLE_DAYS - 1) / CYCLE_DAYS) * CYCLE_DAYS;
        return startDate.plusDays(nextBoundary);
    }

    /**
     * {@code joinDate} 에 가입한 사람의 <b>판정</b> 시작일 — 인증 판정·성공률 같은 통계가 이날부터 잡힌다.
     *
     * <p>진행 중인 방(시작일 다음 날 이후)에 들어오면 <b>가입 다음 날</b>부터다. 가입 당일은 이미 지나간
     * 시간이 있어 그날을 통째로 평가하면 불리하다(09-28 결정, QA JOIN-14). 시작 전·시작일 당일 가입은
     * 시작일부터 — 방장도 여기에 해당한다.
     *
     * <p>점수는 이보다 늦은 {@link #countFrom}(다음 사이클 경계)부터다. 사이클 점수는 주간 목표 횟수로
     * 매기는데, 중간에 들어온 주는 목표를 채울 날이 모자라 그 주를 점수에 넣으면 불리해진다.
     */
    public static LocalDate judgeFrom(LocalDate startDate, LocalDate joinDate) {
        return joinDate.isAfter(startDate) ? joinDate.plusDays(1) : startDate;
    }

    /** 진행 중 입장이라 판정이 가입 다음 날부터 시작되는가(상세 조회 {@code joinNote}). */
    public static boolean startsNextDay(LocalDate startDate, LocalDate joinDate) {
        return !judgeFrom(startDate, joinDate).equals(startDate);
    }
}
