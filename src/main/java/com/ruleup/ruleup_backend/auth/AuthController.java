package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.OAuthLoginRequest;
import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/oauth/{provider}")
    public ApiResponse<OAuthLoginResponse> oauthLogin(
            @PathVariable String provider,
            @RequestBody OAuthLoginRequest request) {
        return ApiResponse.ok(authService.oauthLogin(parseProvider(provider), request));
    }

    /** "kakao" → OAuthProvider.KAKAO (대소문자 무관) */
    private OAuthProvider parseProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }
}