package com.ruleup.ruleup_backend.score.domain;

/**
 * 사건성 감점 — 점수 및 티어 정책 §4.8.
 *
 * <p>루틴 점수와 성질이 다르다. 사이클 순변동 ±20 한도를 <b>거치지 않고</b> 즉시 전액 반영하며,
 * 연속 성공·실패 기록도 건드리지 않는다. 한도는 "한 주에 얼마나 움직일 수 있나"를 다루는 장치인데
 * 부정행위는 주간 성과가 아니기 때문이다.
 *
 * <p><b>연속 실패 강퇴는 여기 없다.</b> 각 주의 루틴 점수에 이미 실패분이 반영돼 있어 강퇴 감점을
 * 중복 부과하지 않는다. 마찬가지로 계정 제재에 따른 자동 탈퇴 · 최소 티어 미달 자동 탈퇴 ·
 * 직권 폐쇄 탈퇴 · 휴면 처리 탈퇴도 감점 이벤트를 만들지 않는다 — 중복 처벌이거나 귀책이 없다.
 */
public enum IncidentType {

    /** 이상패턴 탐지로 확정된 검출 1회. 해당 챌린지 강퇴·영구 차단과 함께 적용되며 강퇴 감점을 중복 부과하지 않는다. */
    CHEAT_DETECTED,

    /** 권한 미허용 강퇴. */
    PERMISSION_KICK,

    /** 중도 탈퇴. 진행 기간에 비례해 줄고 1년 이상 성공했으면 면제된다. */
    VOLUNTARY_LEAVE;

    /** 부정행위 검출 감점(절댓값). */
    private static final int CHEAT = 50;
    /** 강퇴·탈퇴 감점 상한(절댓값). */
    private static final int KICK = 15;
    /** 면제 기준 — 1년. */
    private static final int EXEMPT_WEEKS = 52;

    /**
     * 감점(음수). 0 이면 감점하지 않는다.
     *
     * @param progressWeeks 해당 챌린지의 <b>판정이 완료된</b> 진행 주간 수. 진행 중인 주는 세지 않는다
     */
    public int deduction(int progressWeeks) {
        return switch (this) {
            case CHEAT_DETECTED -> -CHEAT;
            case PERMISSION_KICK -> -KICK;
            // −⌈15 × (1 − 진행주간/52)⌉ — 오래 해온 방일수록 가볍고, 1년을 채웠으면 면제다.
            case VOLUNTARY_LEAVE -> {
                int weeks = Math.max(0, Math.min(EXEMPT_WEEKS, progressWeeks));
                yield -ceilDiv(KICK * (EXEMPT_WEEKS - weeks), EXEMPT_WEEKS);
            }
        };
    }

    /** 올림 나눗셈. 소수를 만들지 않으려고 정수만으로 처리한다. */
    private static int ceilDiv(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
