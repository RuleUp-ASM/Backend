package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * 수면 인증 파라미터(§4.3, §2.17). 둘 중 하나로 판정:
 *  - bedtimeBefore("HH:mm"): 취침(첫 세그먼트 start) ≤ 목표시각 → SLEPT_LATE 방지
 *  - minSleepHours: 수면시간(세그먼트 합) ≥ 목표 → INSUFFICIENT_SLEEP 방지
 * 신호가 익일 아침 도착이라 maxSignalLagHours ≈ 12h.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepConfig(
        String bedtimeBefore,         // "HH:mm" (없으면 null)
        BigDecimal minSleepHours,     // (없으면 null)
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours
) {}
