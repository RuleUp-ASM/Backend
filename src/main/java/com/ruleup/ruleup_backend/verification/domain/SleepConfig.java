package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * 수면 인증 파라미터(§4.3, §2.17). 둘 중 하나로 판정:
 *  - bedtimeBefore("HH:mm"): [목표 - bedtimeLeadMinutes, 목표 + bedtimeGraceMinutes] 창 안에 잠들었는가 → SLEPT_LATE 방지.
 *    창보다 일찍 시작한 잠은 목표 시각에도 이어서 자고 있어야 인정한다(저녁 낮잠은 그 밤의 취침이 아니다)
 *  - minSleepHours: 수면시간(세그먼트 합) ≥ 목표 → INSUFFICIENT_SLEEP 방지
 * 신호가 익일 아침 도착이라 maxSignalLagHours ≈ 12h.
 *
 * <p>{@code trustedOrigins} 는 걸음·거리(HealthConfig)와 같은 뜻의 화이트리스트다 — 스펙이
 * "신뢰 가능한 Health Connect 수면 기록만 사용"이라고 적은 조건이 이것이다. MANUAL 제외만으로는
 * 임의 앱이 AUTO 로 써 넣은 기록이 그대로 통과한다. 비어 있으면 게이트를 적용하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepConfig(
        String bedtimeBefore,         // "HH:mm" (없으면 null)
        BigDecimal minSleepHours,     // (없으면 null)
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours,
        List<String> trustedOrigins,  // dataOrigin 화이트리스트(비면 게이트 미적용)
        int bedtimeLeadMinutes,       // 취침 창: 목표 시각 앞(분)
        int bedtimeGraceMinutes       // 취침 창: 목표 시각 뒤(분)
) {}
