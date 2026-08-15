package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /api/v1/nicknames/check 응답 (스펙 4.6).
 *  - valid     : 형식 통과 여부 (확인 전 단계)
 *  - available : 최종 사용 가능 여부
 *  - reason    : 실패 사유. "FORMAT" / "DUPLICATED" / null(통과)
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
                · DUPLICATED — 이미 사용 중. 검수 중인 신청값과, 변경 심사 중이라 아직 안 풀린 이전 닉네임도 점유로 본다""",
                example = "DUPLICATED",
                allowableValues = {"FORMAT", "DUPLICATED"})
        String reason) {

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
