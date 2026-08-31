package com.ruleup.ruleup_backend.score.domain;

/**
 * 티어 구간과 유예 밴드 — 점수 및 티어 정책 §1.1·§1.2 (2026-08-26 개정).
 *
 * <p>점수는 <b>계정당 하나의 단일 축 0~2,000</b> 이다. 구 체계처럼 티어마다 0~99 로 끊지 않는다.
 * 그래서 승급이 초과 점수를 버리지 않는다 — 98점에서 +4 면 실버 102점이지 실버 2점이 아니다.
 *
 * <p><b>강등에만 유예가 있고 승급에는 없다.</b> 시작점을 넘으면 즉시 올라가지만, 내려갈 때는
 * 시작점보다 1~20점 낮은 동안 표시 티어를 유지하다가 21점 이상 낮아져야 확정된다. 점수가
 * 경계에서 진동할 때 표시 티어가 함께 깜빡이면 방 게이팅(입장 자격)까지 흔들리기 때문이다.
 */
public final class TierBands {

    /** 표시 티어를 유지해 주는 폭. 시작점 −20 까지가 유예, −21 부터가 강등 확정이다. */
    public static final int GRACE_POINTS = 20;

    /** 점수 상한. 루비 안에서도 점수는 계속 쌓이지만 여기서 멈춘다. */
    public static final int MAX_SCORE = 2_000;

    private TierBands() {}

    /** 각 티어의 시작점. UNRANKED 는 티어가 아니라 요약이 아직 없는 상태라 여기 없다. */
    public static long startOf(Tier tier) {
        return switch (tier) {
            case UNRANKED, BRONZE -> 0L;
            case SILVER -> 100L;
            case GOLD -> 300L;
            case DIAMOND -> 500L;
            case RUBY -> 1_000L;
        };
    }

    /** 점수만으로 정해지는 실제 티어. */
    public static Tier of(long score) {
        if (score >= startOf(Tier.RUBY)) return Tier.RUBY;
        if (score >= startOf(Tier.DIAMOND)) return Tier.DIAMOND;
        if (score >= startOf(Tier.GOLD)) return Tier.GOLD;
        if (score >= startOf(Tier.SILVER)) return Tier.SILVER;
        return Tier.BRONZE;
    }

    /** 한 단계 위 티어. 루비는 더 올라갈 곳이 없어 null. */
    public static Tier next(Tier tier) {
        return switch (tier) {
            case UNRANKED, BRONZE -> Tier.SILVER;
            case SILVER -> Tier.GOLD;
            case GOLD -> Tier.DIAMOND;
            case DIAMOND -> Tier.RUBY;
            case RUBY -> null;
        };
    }

    /** 다음 티어 시작점까지 남은 점수. 이미 넘었으면 0. */
    public static long pointsToPromote(long score, Tier next) {
        return Math.max(0, startOf(next) - score);
    }

    /** 표시 티어를 유지해 주는 하한(시작점 −20). 이 점수까지는 강등되지 않는다. */
    public static long graceFloor(Tier displayTier) {
        return startOf(displayTier) - GRACE_POINTS;
    }

    /** 이 점수 이하로 떨어지면 강등이 확정된다(시작점 −21). */
    public static long demoteAt(Tier displayTier) {
        return graceFloor(displayTier) - 1;
    }

    /**
     * 표시 티어가 유예로 버티고 있는 상태인지 — 점수는 이미 시작점 아래인데 아직 강등되지 않았다.
     * 브론즈는 시작점이 0 이라 그 아래가 없으므로 항상 false 다.
     */
    public static boolean isInGraceBand(long score, Tier displayTier) {
        return score < startOf(displayTier) && score >= graceFloor(displayTier);
    }

    /** 브론즈는 더 내려갈 티어가 없어 강등 안내를 내리지 않는다. */
    public static boolean hasDemotion(Tier displayTier) {
        return displayTier != Tier.BRONZE && displayTier != Tier.UNRANKED;
    }

    /**
     * 새 표시 티어 — 승급은 즉시, 강등만 유예한다.
     *
     * <p>큰 감점으로 유예 구간을 한 번에 관통하면 실제 티어까지 바로 내려간다. 유예는 경계에서
     * 점수가 진동할 때 표시가 깜빡이는 것을 막으려는 장치이지 강등을 미루는 장치가 아니다.
     */
    public static Tier displayTier(long score, Tier actualTier, Tier previousDisplayTier) {
        if (actualTier.ordinal() >= previousDisplayTier.ordinal()) return actualTier;   // 승급·동급은 즉시
        if (isInGraceBand(score, previousDisplayTier)) return previousDisplayTier;
        return actualTier;
    }
}
