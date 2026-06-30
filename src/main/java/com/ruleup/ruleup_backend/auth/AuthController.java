package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.*;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 계정/인증 API (스펙 4.1 ~ 4.6).
 * 경로 prefix는 API 계약(§0)에 맞춰 /api/v1/auth, /api/v1/nicknames 를 사용한다.
 */
@Tag(name = "Account", description = "로그인 · 가입 · 토큰 · 닉네임")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 4.1 / 4.2 소셜 로그인.
     * 명세는 /login/kakao, /login/google 로 나뉘지만 처리 로직이 동일하므로
     * {provider} path 변수 하나로 받는다. (kakao | google)
     */
    @Operation(summary = "소셜 로그인", description = "Kakao/Google 인가코드 검증 후 기존/신규 분기")
    @PostMapping("/api/v1/auth/oauth/{provider}")
    public ApiResponse<OAuthLoginResponse> login(@PathVariable String provider,
                                                 @RequestBody OAuthLoginRequest request) {
        return ApiResponse.ok(authService.oauthLogin(parseProvider(provider), request));
    }

    /**
     * iOS 카카오 로그인용 서버 콜백(브리지).
     * 카카오는 redirect_uri를 https만 허용하므로 이 https 주소를 콘솔에 등록한다.
     * 카카오가 여기로 인가코드를 보내면, 앱 커스텀 스킴(ruleup://...)으로 302 넘겨주고
     * 앱은 그 code로 POST /login/{provider} 를 호출해 토큰을 받는다.
     */
    @Operation(summary = "소셜 로그인 서버 콜백(iOS)", description = "https redirect로 받은 인가코드를 앱 딥링크로 중계")
    @GetMapping("/api/v1/auth/oauth/{provider}/callback")
    public ResponseEntity<Void> loginCallback(@PathVariable String provider,
                                              @RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error) {
        parseProvider(provider);   // 알 수 없는 provider 차단
        URI appLink = authService.buildAppCallbackRedirect(provider.toLowerCase(), code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND).location(appLink).build();
    }

    @Operation(summary = "신규 가입 완료", description = "signupToken으로 가입을 마치고 앱 토큰 발급")
    @PostMapping("/api/v1/auth/signup")
    public ApiResponse<SignupResponse> signup(@RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @Operation(summary = "앱 토큰 재발급", description = "refreshToken 회전")
    @PostMapping("/api/v1/auth/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "로그아웃", description = "현재 기기 refreshToken revoke")
    @PostMapping("/api/v1/auth/logout")
    public ApiResponse<Void> logout(@RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.ok();        // 200 + {success:true, data:null, error:null}
    }

    @Operation(summary = "닉네임 형식/중복 검사", description = "valid(형식)·available(중복) 동시 반환")
    @PostMapping("/api/v1/nicknames/check")
    public ApiResponse<NicknameAvailabilityResponse> checkNickname(@RequestBody NicknameCheckRequest request) {
        return ApiResponse.ok(authService.checkNickname(request.nickname()));
    }

    private OAuthProvider parseProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
    }
}