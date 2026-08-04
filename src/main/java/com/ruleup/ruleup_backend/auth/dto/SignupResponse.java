package com.ruleup.ruleup_backend.auth.dto;
import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import com.ruleup.ruleup_backend.user.domain.User;

/**
 * POST /api/v1/auth/signup 응답 — 가입 완료 즉시 앱 토큰 발급.
 * user 블록은 로그인 응답과 동일 스키마 (가입 직후: PENDING 닉네임·BRONZE 10·ACTIVE).
 */
public record SignupResponse(
        boolean isNewUser,
        String accessToken, String refreshToken, String tokenType, Long expiresIn,
        Integer flushIntervalSec,       // sync 주기 부트스트랩 — deviceInfo 확정 저장 시점(§ oauth 스펙 note)
        UserResponse user) {

    public static SignupResponse from(TokenService.TokenPair pair, User user, UserScoreSummary summary,
                                      int flushIntervalSec) {
        return new SignupResponse(true, pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), flushIntervalSec, UserResponse.from(user, summary));
    }
}
