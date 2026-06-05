package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.User;

import java.math.BigDecimal;

/**
 * OAuth 검증 결과. 기존 사용자면 토큰+user, 신규면 signup_token+oauth_profile.
 * 안드 AuthResponse와 동일 구조(쓰지 않는 필드는 null).
 */
public record OAuthLoginResponse(
        boolean isNewUser,
        // 기존 사용자
        String accessToken, String refreshToken, String tokenType, Long expiresIn, UserResponse user,
        // 신규 사용자
        String signupToken, Long signupTokenExpiresIn, OAuthProfileResponse oauthProfile) {

    public record OAuthProfileResponse(String email, String profileImageUrlHint) {}

    public static OAuthLoginResponse existing(TokenService.TokenPair pair, User user, BigDecimal temp) {
        return new OAuthLoginResponse(false,
                pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn(),
                UserResponse.from(user, temp),
                null, null, null);
    }

    public static OAuthLoginResponse newUser(String signupToken, long expiresIn, String email, String imageHint) {
        return new OAuthLoginResponse(true,
                null, null, null, null, null,
                signupToken, expiresIn, new OAuthProfileResponse(email, imageHint));
    }
}