package com.ruleup.ruleup_backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** DELETE /api/v1/users/me 요청 — 실수 방지용 고정 확인 문구(계약의 일부). */
@Schema(name = "WithdrawRequest", description = "회원 탈퇴 요청")
public record WithdrawRequest(

        @Schema(description = "실수 방지용 확인 문구. 정확히 \"탈퇴할게요\" 여야 하며 다르면 400 이고 탈퇴되지 않는다.",
                example = "탈퇴할게요", requiredMode = Schema.RequiredMode.REQUIRED)
        String confirmPhrase) {}
