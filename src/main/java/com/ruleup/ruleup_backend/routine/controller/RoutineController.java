package com.ruleup.ruleup_backend.routine.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.routine.dto.CreateRoutineRequest;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationRequest;
import com.ruleup.ruleup_backend.routine.dto.RoutineRecommendationResponse;
import com.ruleup.ruleup_backend.routine.dto.RoutineResponse;
import com.ruleup.ruleup_backend.routine.service.RoutineRecommendationService;
import com.ruleup.ruleup_backend.routine.service.RoutineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 루틴 API. 모두 로그인 필요.
 *  - 추천(1단계)과 생성(2단계)은 분리: 추천은 저장 없는 초안, 생성 요청의 값이 최종이다.
 *  - 추천은 "제목 → 템플릿 매칭 → 자동/수동 분류", 생성은 "사용자가 고른 방식·목표값을 서버 재검증 후 저장".
 */
@Tag(name = "Routine", description = "루틴 추천(템플릿 매칭) · 생성")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/routines")
@RequiredArgsConstructor
public class RoutineController {

    private final RoutineRecommendationService recommendationService;
    private final RoutineService routineService;

    @Operation(summary = "루틴 추천",
            description = "제목/설명을 미리 정의된 템플릿과 매칭하고, 보유 권한으로 자동·수동 인증을 분류해 추천(초안). "
                    + "grantedPermissions에 기기에서 보유한 권한을 담아 보낸다. 매칭 실패 시 수동 인증만 추천.")
    @PostMapping("/recommendation")
    public ApiResponse<RoutineRecommendationResponse> recommend(
            @RequestBody RoutineRecommendationRequest request) {
        return ApiResponse.ok(recommendationService.recommend(request));
    }

    @Operation(summary = "루틴 생성",
            description = "추천을 수정·확정한 최종값으로 생성. templateId·인증 방식·목표값·권한을 서버가 재검증한 뒤 "
                    + "선택한 인증 방식을 스냅샷으로 저장한다.")
    @PostMapping
    public ApiResponse<RoutineResponse> create(@AuthenticationPrincipal String userId,
                                               @RequestBody CreateRoutineRequest request) {
        return ApiResponse.ok(routineService.create(UUID.fromString(userId), request));
    }
}