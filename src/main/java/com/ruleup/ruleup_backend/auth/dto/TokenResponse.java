package com.ruleup.ruleup_backend.auth.dto;
import com.ruleup.ruleup_backend.auth.TokenService;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, Long expiresIn) {
    public static TokenResponse from(TokenService.TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn());
    }
}