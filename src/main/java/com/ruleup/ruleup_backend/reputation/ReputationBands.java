package com.ruleup.ruleup_backend.reputation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 온도 밴드 라벨 + 다음 목표(마이프로필 §6.1). 앵커·라벨은 온도 계산 스펙 V1 밴드 표에 정합.
 * 진행 바 = (현재 − 이전 앵커) ÷ (target − 이전 앵커).
 */
public final class ReputationBands {

    /** 이전 앵커 후보(진행 바 하한). 밴드 표의 앵커. */
    private static final double[] ANCHORS = {36.5, 50, 60, 70, 75, 80, 85, 90};
    /** 다음 목표 앵커(진행 바 상한). */
    private static final double[] TIERS = {50, 60, 70, 75, 80, 85, 90};

    private ReputationBands() {}

    public record NextTier(BigDecimal target, BigDecimal progressRate, String label) {}

    /** 현재 온도의 밴드 라벨(문구). */
    public static String bandLabel(BigDecimal temp) {
        double x = temp.doubleValue();
        if (x < 36.5) return "회복이 필요한 단계";
        if (x < 40)   return "노력하는 사람";
        if (x < 50)   return "루틴을 적당히 지키는 편";
        if (x < 60)   return "1개를 1년 이상 지키는 편";
        if (x < 70)   return "2개를 1년 이상";
        if (x < 75)   return "3개를 1년 이상 or 1개를 3년 이상";
        if (x < 80)   return "4개를 1년 이상 or 2개를 3년 이상";
        if (x < 85)   return "5개를 1년 이상 or 1개를 5년 이상 or 3개를 3년 이상";
        if (x < 90)   return "3개 이상을 5년 이상";
        return "루틴에 미친 사람";
    }

    /** 다음 목표(target·진행률·라벨). 최상위 밴드면 target=100, 진행률=1. */
    public static NextTier nextTier(BigDecimal temp) {
        double x = temp.doubleValue();
        double prev = 36.5;
        for (double a : ANCHORS) { if (a <= x) prev = a; else break; }
        Double target = null;
        for (double t : TIERS) { if (t > x) { target = t; break; } }
        if (target == null) {
            return new NextTier(new BigDecimal("100.00"), BigDecimal.ONE.setScale(2), "최상위 밴드 달성");
        }
        double progress = (x - prev) / (target - prev);
        progress = Math.max(0.0, Math.min(1.0, progress));
        return new NextTier(
                BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(progress).setScale(2, RoundingMode.HALF_UP),
                "%.0f°C — %s".formatted(target, tierDesc(target)));
    }

    private static String tierDesc(double target) {
        if (target == 50) return "1개를 1년 이상";
        if (target == 60) return "2개를 1년 이상";
        if (target == 70) return "3개×1년 or 1개×3년";
        if (target == 75) return "4개×1년 or 2개×3년";
        if (target == 80) return "5개×1년 or 1개×5년 or 3개×3년";
        if (target == 85) return "3개 이상×5년";
        return "루틴에 미친 사람";
    }
}
