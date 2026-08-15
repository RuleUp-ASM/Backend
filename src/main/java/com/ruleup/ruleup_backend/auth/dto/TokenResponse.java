package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TokenResponse", description = """
        재발급된 토큰 쌍. refreshToken 도 함께 새로 발급되므로(회전) 저장값을 반드시 덮어써야 한다.
        제출한 이전 refreshToken 은 이 시점부터 무효다.""")
public record TokenResponse(

        @Schema(description = "새 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,

        @Schema(description = "새 리프레시 토큰 — 이 값으로 저장된 토큰을 교체한다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJSRU...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken,

        @Schema(description = "토큰 타입. 항상 Bearer.", example = "Bearer")
        String tokenType,

        @Schema(description = "accessToken 만료까지 남은 시간(초)", example = "3600")
        Long expiresIn) {

    public static TokenResponse from(TokenService.TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn());
    }
}
