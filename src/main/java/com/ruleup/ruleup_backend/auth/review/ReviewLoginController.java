package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 심사 코드 로그인 (심사 계정 테크 스펙 5-2). */
@Tag(name = "Auth")
@RestController
@RequiredArgsConstructor
public class ReviewLoginController {

    private final ReviewLoginService reviewLoginService;

    @Schema(name = "ReviewLoginRequest", description = "심사 코드 로그인 요청. 다른 필드(deviceId·deviceInfo·installationId)는 보내도 무시한다.")
    public record ReviewLoginRequest(
            @Schema(description = "심사위원에게 전달한 코드. 대소문자·하이픈·공백은 구분하지 않는다.", example = "K7QM-4XRD-9WTF")
            String code) {}

    @Operation(summary = "심사 코드 로그인")
    @PostMapping("/api/v1/auth/review-login")
    public ApiResponse<OAuthLoginResponse> login(@RequestBody(required = false) ReviewLoginRequest request,
                                                 HttpServletRequest http) {
        String code = (request != null) ? request.code() : null;
        return ApiResponse.ok(reviewLoginService.login(code, ReviewLoginAttemptLimiter.clientIp(http)));
    }
}
