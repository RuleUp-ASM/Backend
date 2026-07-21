package com.ruleup.ruleup_backend.me.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.me.service.MeHomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 마이프로필(마이 탭) API. base=/api/v1/me. 모두 로그인 필요. */
@Tag(name = "Me", description = "마이 홈 · 캘린더 · 통계 · 평판 · 초대")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final MeHomeService homeService;

    @Operation(summary = "마이 홈 일괄 조회", description = "닉네임·검수상태·프로필이미지·매너온도 + 카운트(완주·진행·그룹).")
    @GetMapping("/home")
    public ApiResponse<MeHomeResponse> home(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(homeService.home(UUID.fromString(userId)));
    }
}
