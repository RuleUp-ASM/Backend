package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OAuth 검증 결과. 활성 사용자면 토큰+user, 그 외(신규·탈퇴)면 signupToken+oauthProfile.
 * 탈퇴 계정의 복원은 여기서 하지 않는다 — 가입 요청(POST /auth/signup)에서 처리한다.
 */
@Schema(name = "OAuthLoginResponse", description = """
        소셜 로그인 결과. isNewUser 로 분기한다.
        · false → accessToken·refreshToken·user·device·flushIntervalSec 채워짐 (signupToken 계열 null)
        · true  → signupToken·signupTokenExpiresIn·oauthProfile 채워짐 (토큰 계열 null, 계정 미생성)""")
public record OAuthLoginResponse(

        @Schema(description = "신규 사용자 여부. true 면 아직 계정이 없고 가입(signup)을 마쳐야 한다.",
                example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isNewUser,

        // 기존 사용자
        @Schema(description = "앱 액세스 토큰. 보호 API 에 `Authorization: Bearer {값}` 으로 싣는다. (신규면 null)",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...")
        String accessToken,

        @Schema(description = "앱 리프레시 토큰. 재발급(refresh)에 쓰며 회전되므로 매번 새 값으로 덮어쓴다. (신규면 null)",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJSRU...")
        String refreshToken,

        @Schema(description = "토큰 타입. 항상 Bearer. (신규면 null)", example = "Bearer")
        String tokenType,

        @Schema(description = "accessToken 만료까지 남은 시간(초). (신규면 null)", example = "3600")
        Long expiresIn,

        @Schema(description = """
                인증 신호 전송(sync) 권장 주기(초). 기기 스펙 기반으로 서버가 산정한다.
                기존 회원은 로그인 시점에 바로 내려주고, 신규는 가입 응답에서 받는다.""",
                example = "900")
        Integer flushIntervalSec,       // sync 주기 부트스트랩(기기 스펙 기반 산정) — 기존 회원 즉시 반환

        @Schema(description = "로그인한 사용자 정보. (신규면 null)")
        UserResponse user,

        @Schema(description = "이번 로그인 기준으로 서버에 저장된 기기 스펙(보낸 값을 그대로 되돌려준다). (신규면 null)")
        DeviceSpecResponse device,

        // 신규 사용자
        @Schema(description = """
                가입 전용 1회성 토큰. POST /api/v1/auth/signup 에 그대로 실어 보낸다. (기존 회원이면 null)
                재사용·만료 시 400 INVALID_SIGNUP_TOKEN 이며 소셜 로그인부터 다시 시작해야 한다.""",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJTSUdOVVAi...")
        String signupToken,

        @Schema(description = "signupToken 만료까지 남은 시간(초). (기존 회원이면 null)", example = "600")
        Long signupTokenExpiresIn,

        @Schema(description = "온보딩 화면 프리필 힌트. (기존 회원이면 null)")
        OAuthProfileResponse oauthProfile,

        @Schema(description = """
                예전에 탈퇴한 계정이 있는 소셜 계정인지. isNewUser=true 일 때만 의미가 있다.
                true 면 **온보딩 입력 화면을 띄우지 않고** 곧바로 POST /api/v1/auth/signup 을 호출한다 —
                서버가 이전 정보를 그대로 살려 로그인시키므로 입력받을 게 없다.
                false 면 평소대로 닉네임·관심사·약관을 입력받아 가입을 진행한다.""",
                example = "false")
        Boolean returningUser) {

    /**
     * 온보딩 프리필 힌트 — 닉네임·프로필 사진(·이메일)에만 적용(2026-08-03).
     * birthdayHint/genderHint 는 비즈 앱 미전환·민감 스코프 이슈로 항상 null 고정(추후 대비용 필드 유지).
     */
    @Schema(name = "OAuthProfileResponse", description = """
            소셜 계정에서 가져온 온보딩 프리필 힌트. 그대로 가입되지 않고 입력값의 기본값으로만 쓴다.
            nicknameHint 는 중복일 수 있으므로 POST /api/v1/nicknames/check 로 확인해야 한다.""")
    public record OAuthProfileResponse(

            @Schema(description = "소셜 계정 이메일. 제공자가 주지 않으면 null.", example = "ruleup@kakao.com")
            String email,

            @Schema(description = "소셜 프로필 사진 URL. 서버에 저장되지 않으며 화면 미리보기용이다.",
                    example = "https://k.kakaocdn.net/dn/profile.jpg")
            String profileImageUrlHint,

            @Schema(description = "소셜 계정 닉네임. 중복일 수 있어 그대로 가입되지 않는다.", example = "규칙왕")
            String nicknameHint,

            @Schema(description = "항상 null — 제공자 스코프 제약으로 받지 못한다(향후 대비용 필드).")
            String birthdayHint,

            @Schema(description = "항상 null — 제공자 스코프 제약으로 받지 못한다(향후 대비용 필드).")
            String genderHint) {

        public static OAuthProfileResponse from(OAuthUserInfo info) {
            return new OAuthProfileResponse(info.email(), info.profileImageUrl(),
                    info.nickname(), null, null);
        }
    }

    /** 서버에 저장된 이번 로그인 기준 디바이스 스펙(로그인 시 갱신된 값을 그대로 되돌려줌). */
    @Schema(name = "DeviceSpecResponse", description = "서버에 저장된 이번 로그인 기준 기기 스펙")
    public record DeviceSpecResponse(

            @Schema(description = "플랫폼", example = "ANDROID") String platform,
            @Schema(description = "앱 버전 코드", example = "100") Integer appVersionCode,
            @Schema(description = "앱 버전명", example = "1.0.0") String appVersionName,
            @Schema(description = "OS 버전", example = "14") String osVersion,
            @Schema(description = "안드로이드 SDK 레벨", example = "34") Integer sdkInt,
            @Schema(description = "기기 모델명", example = "SM-S921N") String deviceModel,
            @Schema(description = "제조사", example = "samsung") String manufacturer,
            @Schema(description = "저사양 기기 여부", example = "false") Boolean lowRam) {

        public static DeviceSpecResponse from(User user) {
            return new DeviceSpecResponse(
                    user.getPlatform() != null ? user.getPlatform().name() : null,
                    user.getAppVersionCode(), user.getAppVersionName(),
                    user.getOsVersion(), user.getSdkInt(), user.getDeviceModel(),
                    user.getManufacturer(), user.getLowRam());
        }
    }

    public static OAuthLoginResponse existing(TokenService.TokenPair pair, User user,
                                              UserScoreSummary summary, int flushIntervalSec) {
        return new OAuthLoginResponse(false,
                pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn(),
                flushIntervalSec,
                UserResponse.from(user, summary),
                DeviceSpecResponse.from(user),
                null, null, null, null);
    }

    /** 신규 분기. returningUser=true 면 예전에 탈퇴한 계정이 있어 입력 없이 복원될 사람이다. */
    public static OAuthLoginResponse newUser(String signupToken, long expiresIn, OAuthUserInfo info,
                                             boolean returningUser) {
        return new OAuthLoginResponse(true,
                null, null, null, null, null, null, null,
                signupToken, expiresIn, OAuthProfileResponse.from(info), returningUser);
    }
}
