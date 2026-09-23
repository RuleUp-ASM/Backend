package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 움직임(HEALTH) 인증 파라미터(테크스펙 v2 §8, §12.3) — 도달형.
 * Health Connect readings를 메타데이터 게이트로 거른 뒤 metric 합계 ≥ goal 이면 충족.
 *  - trustedOrigins      : 신뢰 writer 화이트리스트(삼성헬스·구글핏 등). 그 외 origin은 거부(UNTRUSTED_HEALTH_SOURCE).
 *  - rejectManualRecording: recordingMethod==MANUAL(손입력) 거부(§8.2).
 *  - 신호가 익일 정정 가능하도록 maxSignalLagHours(기본 2h, §7.4).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthConfig(
        HealthMetric metric,          // DISTANCE / STEPS / EXERCISE_DURATION
        BigDecimal goal,              // 목표값(km / count / min)
        String unit,                  // "km" / "count" / "min"
        String exerciseType,          // "RUNNING" 등(null=무관)
        List<String> trustedOrigins,  // dataOrigin 화이트리스트
        boolean rejectManualRecording,// MANUAL 기록 거부
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours
) {}
