package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * 수면 인증 파라미터(§4.3, §2.17). 둘 중 하나로 판정:
 *  - bedtimeBefore("HH:mm"): <b>걸음으로 본다</b> — [목표, 목표 + bedtimeWindowMinutes] 동안 걸음 ≤ bedtimeMaxSteps
 *    면 잠자리에 든 것이다 → SLEPT_LATE 방지. 수면 기록은 워치가 없으면 잘 안 남아 2026-10-23 부터 걸음으로 바꿨다
 *  - minSleepHours: 수면시간(수면 기록 세그먼트 합) ≥ 목표 → INSUFFICIENT_SLEEP 방지
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
        List<String> trustedOrigins,  // 수면 기록 dataOrigin 화이트리스트(비면 게이트 미적용)
        int bedtimeWindowMinutes,     // 취침 창: 목표 시각부터(분)
        int bedtimeMaxSteps,          // 취침 창 안에서 허용하는 걸음 수
        List<String> stepOrigins      // 취침 걸음 기록의 신뢰 출처(기상과 같은 목록)
) {}
