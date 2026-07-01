package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Phase 0 인트로 응답 = 서버 sync 정책(§0.3 settings). */
public record VerificationIntroResponse(
        long serverTimeMillis,
        int flushIntervalSec,
        Collection collection,
        Backoff backoff,
        String sessionId
) {
    /** 신호별 수집 주기. enabled 항상 직렬화(primitive), pollSec 은 null 가능. */
    public record Cadence(boolean enabled, Integer pollSec) {}

    /** 키는 Android @SerialName 과 동일하게 대문자로 직렬화. */
    public record Collection(
            @JsonProperty("GEOFENCE") Cadence geofence,
            @JsonProperty("SCREEN_TIME") Cadence screenTime,
            @JsonProperty("WAKE") Cadence wake,
            @JsonProperty("HEALTH") Cadence health
    ) {}

    public record Backoff(int maxSec, double factor) {}
}
