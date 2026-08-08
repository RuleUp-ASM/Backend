package com.ruleup.ruleup_backend.auth.dto;

import java.time.Instant;

/**
 * POST /api/v1/nicknames/check 응답 (스펙 4.6).
 *  - valid       : 형식 통과 여부 (확인 전 단계)
 *  - available   : 최종 사용 가능 여부
 *  - reason      : 실패 사유. "FORMAT" / "DUPLICATED" / "RECENTLY_RELEASED" / null(통과)
 *  - availableAt : RECENTLY_RELEASED 일 때 잠금 해제 시각(ISO), 그 외 null
 *
 * <p>형식 위반도 에러가 아니라 200 + valid:false 로 내린다 — 실시간 확인 UX에서
 * 에러 봉투 분기를 없애기 위함이다. 400 NICKNAME_FORMAT_INVALID 는 가입·변경 제출 시점 전용.
 */
public record NicknameAvailabilityResponse(boolean valid, boolean available,
                                           String reason, String availableAt) {

    public static NicknameAvailabilityResponse formatFail() {
        return new NicknameAvailabilityResponse(false, false, "FORMAT", null);
    }

    public static NicknameAvailabilityResponse duplicated() {
        return new NicknameAvailabilityResponse(true, false, "DUPLICATED", null);
    }

    /** 최근 누군가 변경으로 버린 닉네임 — 1주일 잠금(회원 정책 §3). */
    public static NicknameAvailabilityResponse recentlyReleased(Instant availableAt) {
        return new NicknameAvailabilityResponse(true, false, "RECENTLY_RELEASED", availableAt.toString());
    }

    public static NicknameAvailabilityResponse ok() {
        return new NicknameAvailabilityResponse(true, true, null, null);
    }
}
