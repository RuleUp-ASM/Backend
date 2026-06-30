package com.ruleup.ruleup_backend.auth.dto;
import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.domain.User;
import java.math.BigDecimal;

public record SignupResponse(
        boolean isNewUser,
        String accessToken, String refreshToken, String tokenType, Long expiresIn, UserResponse user) {

    public static SignupResponse from(TokenService.TokenPair pair, User user, BigDecimal temp) {
        return new SignupResponse(true, pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), UserResponse.fromSignup(user, temp));
    }
}