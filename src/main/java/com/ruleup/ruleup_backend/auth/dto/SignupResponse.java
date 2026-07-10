package com.ruleup.ruleup_backend.auth.dto;
import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.domain.User;
import java.math.BigDecimal;

public record SignupResponse(
        boolean isNewUser,
        String accessToken, String refreshToken, String tokenType, Long expiresIn,
        Integer flushIntervalSec,       // sync 주기 부트스트랩 — deviceInfo 확정 저장 시점(§ oauth 스펙 note)
        UserResponse user) {

    public static SignupResponse from(TokenService.TokenPair pair, User user, BigDecimal temp,
                                      int flushIntervalSec) {
        return new SignupResponse(true, pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), flushIntervalSec, UserResponse.fromSignup(user, temp));
    }
}