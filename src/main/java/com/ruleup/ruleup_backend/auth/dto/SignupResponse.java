package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /api/v1/auth/signup 응답 — 가입 완료 즉시 앱 토큰 발급.
 * user 블록은 로그인 응답과 동일 스키마 (가입 직후: PENDING 닉네임·BRONZE 10·ACTIVE).
 */
@Schema(name = "SignupResponse", description = "가입 완료 결과 — 별도 로그인 없이 이 응답의 토큰으로 바로 진입한다")
public record SignupResponse(

        @Schema(description = """
                이번 요청으로 계정이 새로 만들어졌으면 true.
                동시 가입 경합에서 다른 요청이 먼저 같은 소셜 계정을 만든 경우 false 로 내려가며,
                이때도 토큰은 정상 발급되므로 클라이언트는 동일하게 진행하면 된다.""",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isNewUser,

        @Schema(description = "앱 액세스 토큰. 보호 API 에 `Authorization: Bearer {값}` 으로 싣는다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,

        @Schema(description = "앱 리프레시 토큰. 재발급에 쓰며 회전되므로 매번 새 값으로 덮어쓴다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJSRU...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken,

        @Schema(description = "토큰 타입. 항상 Bearer.", example = "Bearer")
        String tokenType,

        @Schema(description = "accessToken 만료까지 남은 시간(초).", example = "3600")
        Long expiresIn,

        @Schema(description = "인증 신호 전송(sync) 권장 주기(초). 제출한 기기 스펙 기준으로 산정된다.",
                example = "900")
        Integer flushIntervalSec,       // sync 주기 부트스트랩 — deviceInfo 확정 저장 시점(§ oauth 스펙 note)

        @Schema(description = "가입한 사용자 정보. 가입 직후에는 nicknameStatus=PENDING · BRONZE 10점 · ACTIVE 다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UserResponse user) {

    public static SignupResponse from(TokenService.TokenPair pair, User user, UserScoreSummary summary,
                                      int flushIntervalSec) {
        return new SignupResponse(true, pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), flushIntervalSec, UserResponse.from(user, summary));
    }
}
