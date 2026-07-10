package com.ruleup.ruleup_backend.reputation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 온도 계산의 순수 함수부(테크스펙 §1.1 페이스 점수, §2 밴드 매핑). 상태 없음 → 단위 테스트 용이.
 */
@Component
@RequiredArgsConstructor
public class TemperatureFormula {

    private final ReputationTemperatureProperties props;

    /**
     * 방별 페이스 점수 c = clamp((r − pivot)/range, −1, +1). (§1.1)
     * r = 성공한 예정 인증일 / 경과한 예정 인증일.
     */
    public double paceScore(double r) {
        double c = (r - props.getPacePivot()) / props.getPaceRange();
        return clamp(c, -1.0, 1.0);
    }

    /**
     * 밴드 매핑 T = f(G), G = V + B (§2). 연속·단조 증가. 하한 5 / 상한 93.
     * 앵커: f(0)=36.5, f(1)=50, f(2)=60, f(3)=70, f(4)=75, f(5)=80, f(6)=85, f(8)=90.
     */
    public BigDecimal temperature(double g) {
        double base = props.getInitialTemperature();   // 36.5
        double t;
        if (g < 0)      t = base + 9.0 * g;                 // 아래로: 하한까지 완만
        else if (g < 1) t = base + 13.5 * g;               // 36.5 → 50
        else if (g < 2) t = 50 + 10.0 * (g - 1);           // 50 → 60
        else if (g < 3) t = 60 + 10.0 * (g - 2);           // 60 → 70
        else if (g < 5) t = 70 + 5.0 * (g - 3);            // 70 → 80
        else if (g < 6) t = 80 + 5.0 * (g - 5);            // 80 → 85
        else if (g < 8) t = 85 + 2.5 * (g - 6);            // 85 → 90
        else            t = 90 + 1.5 * (g - 8);            // 90 → 상한

        t = clamp(t, props.getTemperatureFloor(), props.getTemperatureCeiling());
        return BigDecimal.valueOf(t).setScale(2, RoundingMode.HALF_UP);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
