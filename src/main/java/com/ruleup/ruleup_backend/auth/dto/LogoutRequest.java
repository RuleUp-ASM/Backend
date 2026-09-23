package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LogoutRequest", description = "로그아웃 요청")
public record LogoutRequest(

        @Schema(description = "폐기할 리프레시 토큰. 이미 폐기됐거나 없는 값이어도 성공(멱등)한다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJSRU...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken) {}
