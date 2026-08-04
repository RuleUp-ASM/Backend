package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.User;

/**
 * OAuth 검증 결과. 기존 사용자면 토큰+user, 신규면 signupToken+oauthProfile.
 * restored: 탈퇴 1년 내 동일 소셜 계정 재로그인으로 계정이 복원된 경우 true.
 */
public record OAuthLoginResponse(
        boolean isNewUser,
        Boolean restored,
        // 기존 사용자
        String accessToken, String refreshToken, String tokenType, Long expiresIn,
        Integer flushIntervalSec,       // sync 주기 부트스트랩(기기 스펙 기반 산정) — 기존 회원 즉시 반환
        UserResponse user,
        DeviceSpecResponse device,
        // 신규 사용자
        String signupToken, Long signupTokenExpiresIn, OAuthProfileResponse oauthProfile) {

    /**
     * 온보딩 프리필 힌트 — 닉네임·프로필 사진(·이메일)에만 적용(2026-08-03).
     * birthdayHint/genderHint 는 비즈 앱 미전환·민감 스코프 이슈로 항상 null 고정(추후 대비용 필드 유지).
     */
    public record OAuthProfileResponse(String email, String profileImageUrlHint,
                                       String nicknameHint, String birthdayHint, String genderHint) {

        public static OAuthProfileResponse from(OAuthUserInfo info) {
            return new OAuthProfileResponse(info.email(), info.profileImageUrl(),
                    info.nickname(), null, null);
        }
    }

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

    public static OAuthLoginResponse existing(TokenService.TokenPair pair, User user,
                                              UserScoreSummary summary, int flushIntervalSec,
                                              boolean restored) {
        return new OAuthLoginResponse(false, restored,
                pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn(),
                flushIntervalSec,
                UserResponse.from(user, summary),
                DeviceSpecResponse.from(user),
                null, null, null);
    }

    public static OAuthLoginResponse newUser(String signupToken, long expiresIn, OAuthUserInfo info) {
        return new OAuthLoginResponse(true, null,
                null, null, null, null, null, null, null,
                signupToken, expiresIn, OAuthProfileResponse.from(info));
    }
}
