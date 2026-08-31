package com.ruleup.ruleup_backend.score.domain;

/**
 * 티어별 배점표 — 점수 및 티어 정책 §4.2 · §4.6.
 *
 * <p>티어가 높을수록 <b>적은 실패도 크게 반영</b>된다. 획득은 줄고 감점은 커져서 손익분기 달성률이
 * 브론즈 28.6% 에서 루비 95.0% 까지 올라간다 — 상위 티어를 유지하려면 거의 완주해야 한다는 뜻이다.
 *
 * <p>배점 기준 티어는 <b>사이클 시작 시점의 실제 티어</b>로 고정한다. 주중에 승급·강등이 나도 그 사이클의
 * 배점·판정·보너스는 바뀌지 않는다. 표시 티어는 배점에 쓰지 않는다 — 유예 중인 사용자에게 과도한
 * 불이익이 가기 때문이다.
 */
public final class TierPoints {

    private TierPoints() {}

    /** 주간 목표를 100% 채웠을 때의 총 획득(성공축 W). */
    public static int weeklyGain(Tier tier) {
        return switch (tier) {
            case UNRANKED, BRONZE -> 10;
            case SILVER -> 8;
            case GOLD -> 6;
            case DIAMOND -> 4;
            case RUBY -> 2;
        };
    }

    /** 전량 미달일 때의 총 감점(미달축 W). <b>절댓값</b>이라 부호는 호출부가 붙인다. */
    public static int weeklyPenalty(Tier tier) {
        return switch (tier) {
            case UNRANKED, BRONZE -> 4;
            case SILVER -> 8;
            case GOLD -> 14;
            case DIAMOND -> 23;
            case RUBY -> 38;
        };
    }

    /**
     * 연속 성공 보너스. 사이클마다 1씩 오르되 티어별 천장에서 멈춘다 —
     * 상위 티어일수록 천장이 낮아 연속만으로 빠르게 오르지 못한다.
     */
    public static int streakBonus(Tier tier, int successStreak) {
        if (successStreak < 2) return 0;   // 2사이클부터 붙는다
        return Math.min(successStreak - 1, cap(tier));
    }

    private static int cap(Tier tier) {
        return switch (tier) {
            case UNRANKED, BRONZE -> 5;
            case SILVER -> 4;
            case GOLD -> 3;
            case DIAMOND -> 2;
            case RUBY -> 1;
        };
    }

    /**
     * 연속 실패 추가 감점(음수). 티어와 무관하다 — 각 주의 루틴 점수에 이미 티어별 감점이 들어가 있어
     * 여기까지 티어를 반영하면 이중으로 무거워진다.
     */
    public static int failurePenalty(int failureStreak) {
        if (failureStreak < 2) return 0;
        return -Math.min(failureStreak - 1, 3);
    }
}
