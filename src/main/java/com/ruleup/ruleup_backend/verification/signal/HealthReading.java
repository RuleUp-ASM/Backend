package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Health Connect 읽은 값 1건(테크스펙 v2 §6.2). 움직임(거리·걸음·운동시간) 판정의 원천.
 *  - metric      : DISTANCE|STEPS|EXERCISE_DURATION
 *  - value/unit  : 5.2 / "km|count|min"
 *  - exerciseType: RUNNING 등(null=무관)
 *  - origin      : 신뢰 메타데이터(필수). 없으면 서버 거부(§6.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthReading(
        String metric,
        BigDecimal value,
        String unit,
        String exerciseType,
        HealthOrigin origin
) {}
