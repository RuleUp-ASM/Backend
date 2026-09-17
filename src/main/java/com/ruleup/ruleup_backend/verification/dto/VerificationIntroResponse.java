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
    /**
     * 신호 종류별 수집 정책. <b>인증 방식 6종 중 자동 5종이 모두 있어야 한다</b> —
     * 예전에는 SLEEP 키가 빠져 있어서, 이 응답을 따르는 클라이언트는 수면 챌린지에 참여해도
     * 수집을 시작할 근거가 없었다(QA SIG-10).
     */
    public record Collection(
            @JsonProperty("GEOFENCE") Cadence geofence,
            @JsonProperty("SCREEN_TIME") Cadence screenTime,
            @JsonProperty("WAKE") Cadence wake,
            @JsonProperty("HEALTH") Cadence health,
            @JsonProperty("SLEEP") Cadence sleep
    ) {}

    public record Backoff(int maxSec, double factor) {}
}
