package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Phase 0 인트로 요청(§0.3). 권한 스냅샷은 MVP에선 수신만 하고 정책엔 미반영.
 *
 * <p>{@code deviceId} 는 단일 활성 기기 판정 키다(로그인과 같은 값). 발급하는 세션에 적어 두어야
 * 「어느 기기가 이 정책으로 수집을 시작했는지」를 나중에 되짚을 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationIntroRequest(
        String deviceId,
        DeviceProfile deviceProfile,
        String appVersion,
        Map<String, Object> permissions
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceProfile(Integer sdkInt, String model, Boolean lowRam) {}
}
