package com.ruleup.ruleup_backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원 탈퇴 응답 (회원 탈퇴 API 계약).
 * archiveExpiresAt: 개인정보 아카이브 파기 예정 시각(탈퇴 +1년) — 이 안에 재로그인하면 복원.
 */
@Schema(name = "WithdrawResponse", description = "회원 탈퇴 결과")
public record WithdrawResponse(

        @Schema(description = "탈퇴 처리 여부. 멱등이라 이미 탈퇴한 계정도 true 다.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean withdrawn,

        @Schema(description = """
                개인정보 아카이브 파기 예정 시각(ISO-8601, 탈퇴 +1년).
                이 시각 전에 같은 소셜 계정으로 로그인하면 신규 가입이 아니라 복원이다.""",
                example = "2027-08-15T06:20:11Z")
        String archiveExpiresAt,

        @Schema(description = "화면에 그대로 보여줄 안내 문구",
                example = "1년 안에 같은 소셜 계정으로 로그인하면 기록이 복원돼요")
        String restoreNote) {}
