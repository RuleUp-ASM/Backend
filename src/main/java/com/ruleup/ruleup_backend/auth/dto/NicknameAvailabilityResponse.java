package com.ruleup.ruleup_backend.auth.dto;

/**
 * POST /api/v1/nicknames/check 응답 (스펙 4.6).
 *  - valid     : 형식 통과 여부 (확인 전 단계)
 *  - available : 사용 가능 여부 (중복 확인 후 단계)
 *  - reason    : 실패 사유. "FORMAT"(형식 실패) / "DUPLICATED"(중복) / null(통과)
 */
public record NicknameAvailabilityResponse(boolean valid, boolean available, String reason) {

    public static NicknameAvailabilityResponse formatFail() {
        return new NicknameAvailabilityResponse(false, false, "FORMAT");
    }

    public static NicknameAvailabilityResponse duplicated() {
        return new NicknameAvailabilityResponse(true, false, "DUPLICATED");
    }

    public static NicknameAvailabilityResponse ok() {
        return new NicknameAvailabilityResponse(true, true, null);
    }
}