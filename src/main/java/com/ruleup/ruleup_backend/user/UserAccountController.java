package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.dto.UserMeResponse;
import com.ruleup.ruleup_backend.user.dto.WithdrawRequest;
import com.ruleup.ruleup_backend.user.dto.WithdrawResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 회원 계정 API — 내 프로필 조회 / 회원 탈퇴 (소셜 로그인·온보딩 모듈 계약 #9·#10).
 */
@Tag(name = "Account", description = "내 프로필 · 회원 탈퇴")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class UserAccountController {

    private final UserAccountService userAccountService;

    @Operation(summary = "내 프로필 조회",
            description = "로그인 응답의 user 블록 + 본인만 볼 수 있는 생일·성별·약관 동의 상태")
    @GetMapping("/api/v1/users/me")
    public ApiResponse<UserMeResponse> me(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userAccountService.me(UUID.fromString(userId)));
    }

    @Operation(summary = "회원 탈퇴",
            description = "확인 문구(\"탈퇴할게요\") 검증 후 소프트 탈퇴 — 1년 내 재로그인 시 복원")
    @DeleteMapping("/api/v1/users/me")
    public ApiResponse<WithdrawResponse> withdraw(@AuthenticationPrincipal String userId,
                                                  @RequestBody WithdrawRequest request) {
        return ApiResponse.ok(userAccountService.withdraw(UUID.fromString(userId), request.confirmPhrase()));
    }
}
