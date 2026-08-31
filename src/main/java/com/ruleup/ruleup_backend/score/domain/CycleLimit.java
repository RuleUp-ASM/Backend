package com.ruleup.ruleup_backend.score.domain;

/**
 * 사이클 순변동 상·하한 — 점수 및 티어 정책 §4.7.
 *
 * <p>한 사이클에서 루틴 점수가 지나치게 크게 움직이지 않도록 <b>챌린지별 각 사이클</b>의 순변동을
 * ±20점으로 제한한다. 달력 주차도 계정 합산도 아니다 — 상태가 사이클 행 하나에 들어 있어 잠금과
 * 정정 재계산이 단순해지고, "어느 챌린지가 한도를 썼는지"를 설명할 수 있다.
 * (여러 챌린지를 병행하면 계정 전체 하락이 한 주에 20점을 넘을 수 있는데, 이는 의도된 동작이고
 *  출시 후 모니터링 항목이다.)
 *
 * <p>상승과 하락을 <b>따로 세지 않는다.</b> 사이클의 원점수 누계 하나를 클램핑한 값이 반영 누계이고,
 * 각 이벤트의 반영분은 그 차분이다. 이 구조라야 "한도에 닿았다 되돌아오는" 구간이 자연스럽게 처리된다 —
 * 원점수 +25 → −5 면 순변동이 여전히 +20 이라 반영은 그대로고, +25 → −10 이면 +15 로 함께 내려온다.
 *
 * <p><b>핵심은 {@code limitedCumulative} 를 {@code target} 으로 덮어쓰지 않는 것이다.</b>
 * 실제로 점수가 움직인 만큼({@code appliedDelta})만 전진시킨다. 덮어쓰면 0점 계정에서 반영되지도 않은
 * 감점이 한도를 소비하고, 이후 원점수가 회복될 때 <b>받은 적 없는 점수가 지급된다</b>.
 *
 * <p>사건성 감점은 이 로직을 거치지 않는다(§4.8). 가입 시 지급하는 10점도 마찬가지다.
 */
public final class CycleLimit {

    /** 사이클 순변동 한도. 상·하한이 대칭이다. */
    public static final int MAX_SWING = 20;

    private CycleLimit() {}

    /**
     * @param rawDelta          이번 이벤트의 원점수 변화량
     * @param rawCumulative     사이클 원점수 누계(직전)
     * @param limitedCumulative 사이클 반영 누계(직전)
     * @param scoreBefore       계정 누적 점수(직전)
     */
    public static Result apply(int rawDelta, int rawCumulative, int limitedCumulative, long scoreBefore) {
        int newRaw = rawCumulative + rawDelta;                       // 원점수는 전액 누적
        int target = Math.max(-MAX_SWING, Math.min(MAX_SWING, newRaw));
        int limitedDelta = target - limitedCumulative;

        long scoreAfter = Math.max(0, Math.min(TierBands.MAX_SCORE, scoreBefore + limitedDelta));
        int appliedDelta = (int) (scoreAfter - scoreBefore);

        // 실제로 움직인 만큼만 전진 — 0점·2,000점 경계에서 잘린 부분은 한도를 소비하지 않는다.
        return new Result(rawDelta, limitedDelta, appliedDelta, newRaw,
                limitedCumulative + appliedDelta, scoreAfter);
    }

    /**
     * @param rawDelta          정책상 원래 계산된 값
     * @param limitedDelta      사이클 순변동 한도를 적용한 값
     * @param appliedDelta      0~2,000 범위까지 적용한 실제 반영량 — 셋을 모두 남겨야 감사와 재계산이 된다
     * @param rawCumulative     갱신된 사이클 원점수 누계
     * @param limitedCumulative 갱신된 사이클 반영 누계
     * @param scoreAfter        반영 후 계정 누적 점수
     */
    public record Result(int rawDelta, int limitedDelta, int appliedDelta,
                         int rawCumulative, int limitedCumulative, long scoreAfter) {

        /** 사이클 한도 때문에 잘렸는지 — 원장에 남겨 한도 도달률을 관찰한다. */
        public boolean cycleLimitApplied() { return limitedDelta != rawDelta; }
    }
}
