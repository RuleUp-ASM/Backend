package com.ruleup.ruleup_backend.score.domain;

/**
 * 사이클 판정 — 점수 및 티어 정책 §4.6.
 *
 * <p>이 판정은 점수 자체보다 <b>연속 기록</b>을 위해 있다. 그리고 사이클 실패 카운트는 방 내부
 * 모듈이 2사이클 경고 · 3사이클 강퇴를 집행하는 입력이기도 하다 — 정의는 여기가 원본이고
 * 집행은 저쪽이다. 점수 도메인은 강퇴를 직접 실행하지 않는다.
 */
public enum CycleResult {

    /** 목표 100% 충족. 연속 성공 +1, 연속 실패 0. */
    SUCCESS,

    /** 달성률 50% 초과 100% 미만. 연속 성공 유지, 연속 실패 0. 추가 점수는 없다. */
    PARTIAL,

    /** 달성률 50% 이하. 연속 성공 0, 연속 실패 +1. */
    FAILURE;

    /**
     * 달성률 판정. <b>부동소수점 나눗셈을 쓰지 않는다</b> — 정수 비교(성공 × 2 &gt; 목표)로 한다.
     * 정확히 50% 는 실패다("50% 이하").
     */
    public static CycleResult of(int successCount, int targetCount) {
        if (successCount >= targetCount) return SUCCESS;
        return (successCount * 2 > targetCount) ? PARTIAL : FAILURE;
    }
}
