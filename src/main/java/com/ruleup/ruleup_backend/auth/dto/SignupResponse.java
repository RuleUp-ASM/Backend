package com.ruleup.ruleup_backend.auth.dto;
import com.ruleup.ruleup_backend.auth.TokenService;
import com.ruleup.ruleup_backend.user.domain.User;
import java.math.BigDecimal;

public record SignupResponse(
        String accessToken, String refreshToken, String tokenType, Long expiresIn, UserResponse user) {

    public static SignupResponse from(TokenService.TokenPair pair, User user, BigDecimal temp) {
        return new SignupResponse(pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.expiresIn(), UserResponse.from(user, temp));
    }
}