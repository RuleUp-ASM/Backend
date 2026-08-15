package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RefreshRequest", description = "토큰 재발급 요청")
public record RefreshRequest(

        @Schema(description = "저장해 둔 리프레시 토큰. 이 호출로 무효화되고 새 값으로 회전된다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJSRU...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken) {}
