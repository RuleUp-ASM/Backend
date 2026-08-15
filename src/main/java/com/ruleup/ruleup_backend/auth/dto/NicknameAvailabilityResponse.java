package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "NicknameAvailabilityResponse", description = """
        닉네임 검사 결과. 실패도 에러가 아니라 200 으로 내려간다.
        UI 는 available 로 제출 버튼을 열고, 문구는 reason 으로 고른다.""")
public record NicknameAvailabilityResponse(

        @Schema(description = "형식 통과 여부(중복 검사 이전 단계). 2~12자·한글/영문/숫자 규칙을 만족하면 true.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean valid,

        @Schema(description = """
                최종 사용 가능 여부. 이 검사를 통과해도 제출 시점에 다른 사람이 먼저 가져갈 수 있으므로,
                가입·변경 응답의 409 도 처리해야 한다.""",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean available,

        @Schema(description = """
                실패 사유. 사용 가능하면 null.
                · FORMAT — 형식 위반
                · DUPLICATED — 이미 사용 중(검수 중인 신청도 점유로 본다)
                · RECENTLY_RELEASED — 최근 변경으로 버려져 1주일간 잠긴 닉네임""",
                example = "DUPLICATED",
                allowableValues = {"FORMAT", "DUPLICATED", "RECENTLY_RELEASED"})
        String reason,

        @Schema(description = "잠금 해제 시각(ISO-8601). reason=RECENTLY_RELEASED 일 때만 채워진다.",
                example = "2026-08-22T04:11:07Z")
        String availableAt) {

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
