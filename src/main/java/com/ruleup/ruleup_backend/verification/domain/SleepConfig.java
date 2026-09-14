package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * 수면 인증 파라미터(§4.3, §2.17). 둘 중 하나로 판정:
 *  - bedtimeBefore("HH:mm"): 취침(첫 세그먼트 start) ≤ 목표시각 → SLEPT_LATE 방지
 *  - minSleepHours: 수면시간(세그먼트 합) ≥ 목표 → INSUFFICIENT_SLEEP 방지
 * 신호가 익일 아침 도착이라 maxSignalLagHours ≈ 12h.
 *
 * <p>{@code trustedOrigins} 는 걸음·거리({@code HealthConfig})와 <b>같은 신뢰 목록</b>이다.
 * 같은 Health Connect 기록인데 수면만 출처를 안 보는 것은 일관되지 않다 — 손입력만 거르면
 * 임의의 {@code dataOrigin} 을 단 자동 기록이 그대로 통과한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepConfig(
        String bedtimeBefore,         // "HH:mm" (없으면 null)
        BigDecimal minSleepHours,     // (없으면 null)
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours,
        List<String> trustedOrigins   // 비면 목록 검사를 하지 않는다(걸음·거리와 같은 규칙)
) {}
