package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.domain.User;

import java.math.BigDecimal;

/**
 * OAuth 검증 결과. 기존 사용자면 토큰+user, 신규면 signup_token+oauth_profile.
 * 안드 AuthResponse와 동일 구조(쓰지 않는 필드는 null).
 */
public record OAuthLoginResponse(
        boolean isNewUser,
        // 기존 사용자
        String accessToken, String refreshToken, String tokenType, Long expiresIn, UserResponse user,
        DeviceSpecResponse device,
        // 신규 사용자
        String signupToken, Long signupTokenExpiresIn, OAuthProfileResponse oauthProfile) {

    public record OAuthProfileResponse(String email, String profileImageUrlHint) {}

    /** 서버에 저장된 이번 로그인 기준 디바이스 스펙(로그인 시 갱신된 값을 그대로 되돌려줌). */
    public record DeviceSpecResponse(
            String platform, Integer appVersionCode, String appVersionName,
            String osVersion, Integer sdkInt, String deviceModel, String manufacturer, Boolean lowRam) {

        public static DeviceSpecResponse from(User user) {
            return new DeviceSpecResponse(
                    user.getPlatform() != null ? user.getPlatform().name() : null,
                    user.getAppVersionCode(), user.getAppVersionName(),
                    user.getOsVersion(), user.getSdkInt(), user.getDeviceModel(),
                    user.getManufacturer(), user.getLowRam());
        }
    }

    public static OAuthLoginResponse existing(TokenService.TokenPair pair, User user, BigDecimal temp) {
        return new OAuthLoginResponse(false,
                pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn(),
                UserResponse.from(user, temp),
                DeviceSpecResponse.from(user),
                null, null, null);
    }

    public static OAuthLoginResponse newUser(String signupToken, long expiresIn, String email, String imageHint) {
        return new OAuthLoginResponse(true,
                null, null, null, null, null, null,
                signupToken, expiresIn, new OAuthProfileResponse(email, imageHint));
    }
}