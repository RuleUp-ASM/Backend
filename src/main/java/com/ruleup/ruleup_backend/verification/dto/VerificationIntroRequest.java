package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Phase 0 인트로 요청(§0.3). 권한 스냅샷은 MVP에선 수신만 하고 정책엔 미반영. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationIntroRequest(
        DeviceProfile deviceProfile,
        String appVersion,
        Map<String, Object> permissions
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceProfile(Integer sdkInt, String model, Boolean lowRam) {}
}
